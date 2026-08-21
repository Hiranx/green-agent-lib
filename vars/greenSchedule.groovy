/**
 * greenSchedule.groovy
 *
 * Jenkins Shared Library:
 *     greenSchedule()
 *
 * Unified High-Confidence Green Scheduling.
 *
 * Combines two carbon-aware schedulers to produce ONE scheduled time and ONE
 * pre-selected deploy strategy — so build + test + deploy all happen at the
 * same green window with zero mid-pipeline waiting.
 *
 * Scheduler 1 — ML Build Optimizer (forecast-based, already run in Analyze stage)
 *   Provides: action (schedule|execute_now), scheduled_hour, green_probability
 *
 * Scheduler 2 — Green AI Agent (real-time, queried here in single-shot mode)
 *   Provides: decision (deploy|wait), next_green_window, confidence, strategy
 *
 * Confidence formula:
 *   combined_confidence = (ml_green_probability × 0.5) + (ai_confidence × 0.5)
 *
 * Hour resolution (priority order):
 *   1. Developer override (OVERRIDE_SCHEDULE_HOUR param)
 *   2. Both agree within ±1 hour → use ML hour (better forecast precision)
 *   3. Both disagree → later of the two (conservative, both windows satisfied)
 *   4. One scheduler unreachable → use the other's recommendation
 *   5. Both unreachable → execute_now with rolling strategy
 *
 * Returns a SchedulingDecision map:
 *   shouldSchedule       — boolean: true = reschedule, false = run now
 *   scheduledHour        — int: target hour 0–23
 *   delaySeconds         — int: seconds from now until target hour
 *   combinedConfidence   — double: 0.0–1.0
 *   preSelectedStrategy  — String: canary | rolling | recreate
 *   mlGreenProbability   — double: raw ML optimizer signal
 *   aiConfidence         — double: raw AI agent signal
 *   mlScheduledHour      — int: ML optimizer's recommended hour
 *   aiNextWindowHour     — int: AI agent's next green window hour (-1 if unknown)
 *   bothAgree            — boolean: both pointed to same hour (±1h)
 *   reason               — String: human-readable explanation
 */

def call(Map config = [:]) {

    // ================================================================
    // CONFIGURATION
    // ================================================================

    def agentUrl = config.agentUrl ?:
                   env.GREEN_AGENT_URL ?:
                   'http://172.17.0.1:5002'

    agentUrl = agentUrl.toString().replaceAll('/+$', '')

    def jobName     = (env.JOB_NAME     ?: 'unknown').toString()
    def buildNumber = (env.BUILD_NUMBER ?: '?').toString()

    def overrideHour = (config.overrideHour ?: 'auto').toString().trim()
    def urgentDeploy = config.urgentDeploy == true ||
                       env.URGENT_DEPLOY == 'true'

    // ML Optimizer outputs — already in env vars from the Analyze stage
    def mlAction           = (config.schedulingAction ?: env.SCHEDULING_ACTION ?: 'execute_now').toString().trim()
    def mlScheduledHourStr = (config.scheduledHour   ?: env.SCHEDULED_HOUR   ?: '0').toString().trim()
    def mlGreenProbStr     = (config.greenProbability ?: env.GREEN_PROBABILITY ?: '0.5').toString().trim()

    def mlScheduledHour = mlScheduledHourStr.isInteger() ? mlScheduledHourStr.toInteger() : 0
    def mlGreenProb     = 0.5d
    try { mlGreenProb = mlGreenProbStr.toDouble() } catch (ignored) {}


    // ================================================================
    // URGENT BYPASS
    // ================================================================

    if (urgentDeploy) {

        echo '⚡ Urgent deployment — skipping Green Scheduling'

        return [
            shouldSchedule      : false,
            scheduledHour       : 0,
            delaySeconds        : 0,
            combinedConfidence  : 1.0d,
            preSelectedStrategy : 'rolling',
            mlGreenProbability  : mlGreenProb,
            aiConfidence        : 1.0d,
            mlScheduledHour     : mlScheduledHour,
            aiNextWindowHour    : -1,
            bothAgree           : false,
            reason              : 'Urgent deployment — green scheduling bypassed'
        ]
    }


    // ================================================================
    // SINGLE-SHOT GREEN AI AGENT QUERY
    // ================================================================
    //
    // One HTTP call only. No waiting loop. The response gives us:
    //   - current deploy decision (deploy / wait)
    //   - next green window (if wait)
    //   - deploy strategy (canary / rolling / recreate)
    //   - confidence score
    //
    // Timeout: 30 seconds. On failure → safe fallback values.
    // ================================================================

    def aiDecision       = 'deploy'
    def aiStrategy       = 'rolling'
    def aiConfidence     = 0.5d
    def aiNextWindowHour = -1
    def aiAgentReachable = false

    def requestFile  = 'gs_ai_request.json'
    def responseFile = 'gs_ai_response.json'

    def requestPayload = groovy.json.JsonOutput.toJson([
        job_name     : jobName,
        build_number : buildNumber
    ])

    writeFile(file: requestFile, text: requestPayload)

    withEnv(["GREEN_AGENT_URL=${agentUrl}"]) {

        def curlStatus = sh(
            script: '''
                set +e
                rm -f gs_ai_response.json

                curl -sS -f \
                    --connect-timeout 10 \
                    --max-time 30 \
                    --retry 0 \
                    -X POST \
                    "${GREEN_AGENT_URL}/api/check" \
                    -H "Content-Type: application/json" \
                    --data-binary "@gs_ai_request.json" \
                    -o gs_ai_response.json

                EXIT_CODE=$?
                if [ "$EXIT_CODE" -ne 0 ]; then
                    echo "[greenSchedule] AI Agent request failed (exit ${EXIT_CODE})."
                    exit "$EXIT_CODE"
                fi
                if [ ! -s gs_ai_response.json ]; then
                    echo "[greenSchedule] AI Agent returned empty response."
                    exit 4
                fi
                exit 0
            ''',
            returnStatus: true
        )

        if (curlStatus == 0) {

            aiAgentReachable = true

            try {

                def raw    = readFile(responseFile).trim()
                def parsed = new groovy.json.JsonSlurper().parseText(raw)

                if (parsed.decision != null) {
                    aiDecision = parsed.decision.toString().toLowerCase().trim()
                }
                if (parsed.strategy != null) {
                    aiStrategy = parsed.strategy.toString().toLowerCase().trim()
                }
                if (parsed.confidence != null) {
                    try { aiConfidence = parsed.confidence.toString().toDouble() } catch (ignored) {}
                }

                // Parse next_green_window to extract an hour integer.
                // The agent may return strings like "03:00", "2026-08-21T03:00:00", "3AM", etc.
                if (parsed.next_green_window) {
                    def windowStr = parsed.next_green_window.toString().trim()
                    def hourMatch = (windowStr =~ /(?:T|^|\s)(\d{1,2})[:hH]/)
                    if (hourMatch) {
                        try { aiNextWindowHour = hourMatch[0][1].toInteger() } catch (ignored) {}
                    }
                }

                parsed = null

            } catch (Exception e) {
                echo "[greenSchedule] Could not parse AI Agent response: ${e.message}"
                echo "[greenSchedule] Using AI fallback values."
                aiAgentReachable = false
            }

        } else {
            echo "[greenSchedule] ⚠️ Green AI Agent unreachable (curl exit ${curlStatus}). Using ML Optimizer alone."
        }
    }

    // Validate strategy
    if (!(aiStrategy in ['rolling', 'canary', 'recreate'])) {
        echo "[greenSchedule] ⚠️ Invalid strategy '${aiStrategy}' from AI Agent. Falling back to rolling."
        aiStrategy = 'rolling'
    }


    // ================================================================
    // DEVELOPER OVERRIDE
    // ================================================================

    if (overrideHour != 'auto') {

        def targetHour = 0
        def parseOk    = true
        try { targetHour = overrideHour.toInteger() } catch (ignored) {
            echo "[greenSchedule] ⚠️ Invalid OVERRIDE_SCHEDULE_HOUR '${overrideHour}'. Treating as auto."
            parseOk = false
        }

        if (parseOk) {
            def delaySecs = _computeDelaySeconds(targetHour)

            echo '════════════════════════════════════════════'
            echo ' 🌿 GREEN SCHEDULING — DEVELOPER OVERRIDE'
            echo " Target Hour          : ${targetHour}:00"
            echo " Delay                : ${delaySecs}s"
            echo " Pre-selected Strategy: ${aiStrategy}"
            echo '════════════════════════════════════════════'

            return [
                shouldSchedule      : true,
                scheduledHour       : targetHour,
                delaySeconds        : delaySecs,
                // When scheduling, high ML scheduling signal = low greenProb → high confidence
                combinedConfidence  : ((1.0d - mlGreenProb) * 0.5d) + (aiConfidence * 0.5d),
                preSelectedStrategy : aiStrategy,
                mlGreenProbability  : mlGreenProb,
                aiConfidence        : aiConfidence,
                mlScheduledHour     : mlScheduledHour,
                aiNextWindowHour    : aiNextWindowHour,
                bothAgree           : false,
                reason              : "Developer override — scheduled for ${targetHour}:00"
            ]
        }
    }


    // ================================================================
    // COMBINED SCHEDULING DECISION
    // ================================================================

    def mlWants = (mlAction == 'schedule')
    def aiWants = (aiDecision == 'wait' && aiAgentReachable)

    // --- Neither wants to wait → green now ---
    if (!mlWants && !aiWants) {

        // When executing now, high mlGreenProb = confident it IS green → high confidence
        def combinedConf = (mlGreenProb * 0.5d) + (aiConfidence * 0.5d)

        echo '════════════════════════════════════════════'
        echo ' 🌿 GREEN SCHEDULING — EXECUTE NOW'
        echo " ML Probability       : ${String.format('%.2f', mlGreenProb)}"
        echo " AI Confidence        : ${String.format('%.2f', aiConfidence)}"
        echo " Combined Confidence  : ${String.format('%.2f', combinedConf)}"
        echo " Pre-selected Strategy: ${aiStrategy}"
        echo ' Both schedulers agree: green window right now!'
        echo '════════════════════════════════════════════'

        return [
            shouldSchedule      : false,
            scheduledHour       : 0,
            delaySeconds        : 0,
            combinedConfidence  : combinedConf,
            preSelectedStrategy : aiStrategy,
            mlGreenProbability  : mlGreenProb,
            aiConfidence        : aiConfidence,
            mlScheduledHour     : mlScheduledHour,
            aiNextWindowHour    : aiNextWindowHour,
            bothAgree           : true,
            reason              : 'Both schedulers confirm a green window right now'
        ]
    }

    // --- At least one wants to wait → resolve target hour ---
    def resolvedHour = _resolveTargetHour(
        mlWants, mlScheduledHour,
        aiWants, aiNextWindowHour
    )

    def bothAgree    = mlWants && aiWants && aiNextWindowHour >= 0 &&
                       (Math.abs(mlScheduledHour - aiNextWindowHour) <= 1)

    def delaySecs    = _computeDelaySeconds(resolvedHour)
    // When scheduling, invert mlGreenProb: low greenProb = very confident it's NOT green now
    def combinedConf = ((1.0d - mlGreenProb) * 0.5d) + (aiConfidence * 0.5d)

    def reasonParts = []
    if (mlWants) {
        reasonParts << "ML Optimizer recommends ${mlScheduledHour}:00 (prob=${String.format('%.2f', mlGreenProb)})"
    }
    if (aiWants) {
        def windowLabel = aiNextWindowHour >= 0 ? "${aiNextWindowHour}:00" : 'unknown'
        reasonParts << "AI Agent recommends waiting until ${windowLabel} (conf=${String.format('%.2f', aiConfidence)})"
    }
    if (!aiAgentReachable) {
        reasonParts << 'AI Agent unreachable — using ML Optimizer decision alone'
    }
    def reason = (reasonParts.join('; ')) + ". Resolved to ${resolvedHour}:00."

    echo '════════════════════════════════════════════'
    echo ' 🌿 GREEN SCHEDULING — RESCHEDULING'
    echo " ML wants schedule    : ${mlWants} → ${mlScheduledHour}:00"
    echo " AI wants wait        : ${aiWants} → ${aiNextWindowHour >= 0 ? aiNextWindowHour + ':00' : 'unknown'}"
    echo " Resolved hour        : ${resolvedHour}:00 (in ${delaySecs}s)"
    echo " ML Probability       : ${String.format('%.2f', mlGreenProb)}"
    echo " AI Confidence        : ${String.format('%.2f', aiConfidence)}"
    echo " Combined Confidence  : ${String.format('%.2f', combinedConf)}"
    echo " Both schedulers agree: ${bothAgree}"
    echo " Pre-selected Strategy: ${aiStrategy}"
    echo " Reason               : ${reason}"
    echo '════════════════════════════════════════════'

    return [
        shouldSchedule      : true,
        scheduledHour       : resolvedHour,
        delaySeconds        : delaySecs,
        combinedConfidence  : combinedConf,
        preSelectedStrategy : aiStrategy,
        mlGreenProbability  : mlGreenProb,
        aiConfidence        : aiConfidence,
        mlScheduledHour     : mlScheduledHour,
        aiNextWindowHour    : aiNextWindowHour,
        bothAgree           : bothAgree,
        reason              : reason
    ]
}


// ================================================================
// PRIVATE HELPERS
// ================================================================

/**
 * Resolve the target hour from ML and AI signals.
 *
 * Rules:
 *   Only ML wants to schedule    → ML hour
 *   Only AI wants to wait        → AI hour (or current hour + 1 if unknown)
 *   Both want to wait AND agree  → ML hour (better forecast precision)
 *   Both want to wait AND disagree → later of the two (conservative)
 */
private int _resolveTargetHour(
    boolean mlWants, int mlHour,
    boolean aiWants, int aiHour
) {
    if (mlWants && !aiWants) {
        return mlHour
    }
    if (!mlWants && aiWants) {
        return aiHour >= 0 ? aiHour : ((new Date().getHours() + 1) % 24)
    }
    // Both want to wait
    if (aiHour < 0) {
        return mlHour  // AI window unknown — trust ML
    }
    def diff = Math.abs(mlHour - aiHour)
    if (diff <= 1) {
        return mlHour  // Agree — ML hour (better precision)
    }
    // Disagree — take the later of the two, accounting for midnight wrap
    // E.g. ML=23, AI=1 → both are "tonight/early morning"; later in the
    // overnight cycle is AI=1 (next day), so we use AI.
    if (mlHour > aiHour) {
        return (mlHour - aiHour <= 12) ? mlHour : aiHour
    } else {
        return (aiHour - mlHour <= 12) ? aiHour : mlHour
    }
}

/**
 * Compute delay in seconds from now until the given target hour.
 * Always returns a positive value (schedules for today or tomorrow).
 */
private int _computeDelaySeconds(int targetHour) {
    def hoursToWait = targetHour - new Date().getHours()
    if (hoursToWait <= 0) {
        hoursToWait += 24
    }
    return hoursToWait * 3600
}
