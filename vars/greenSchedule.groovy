/**
 * greenSchedule.groovy
 *
 * Jenkins Shared Library:
 *     greenSchedule()
 *
 * Unified High-Confidence Green Scheduling.
 *
 * Combines two carbon-aware models by separating their concerns:
 *
 * Scheduler 1 — ML Build Optimizer (LightGBM)
 *   Decides the "When": (execute_now | schedule), scheduled_hour, green_probability
 *
 * Scheduler 2 — Green AI Agent (Ollama LLM)
 *   Decides the "How": deploy strategy (canary | rolling | recreate)
 *
 * Returns a SchedulingDecision map:
 *   shouldSchedule       — boolean: true = reschedule, false = run now
 *   scheduledHour        — int: target hour 0–23
 *   delaySeconds         — int: seconds from now until target hour
 *   preSelectedStrategy  — String: canary | rolling | recreate
 *   mlGreenProbability   — double: raw ML optimizer signal (confidence in scheduling)
 *   aiConfidence         — double: raw AI agent signal (confidence in strategy)
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
            preSelectedStrategy : 'rolling',
            mlGreenProbability  : mlGreenProb,
            aiConfidence        : 1.0d,
            reason              : 'Urgent deployment — green scheduling bypassed'
        ]
    }


    // ================================================================
    // GREEN AI AGENT STRATEGY QUERY
    // ================================================================
    //
    // One HTTP call only. The response gives us:
    //   - deploy strategy (canary / rolling / recreate)
    //   - confidence score
    //
    // Timeout: 180 seconds. On failure → safe fallback values.
    // ================================================================

    def aiStrategy       = 'rolling'
    def aiConfidence     = 0.5d
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

                curl -sS -f \\
                    --connect-timeout 10 \\
                    --max-time 180 \\
                    --retry 0 \\
                    -X POST \\
                    "${GREEN_AGENT_URL}/api/check" \\
                    -H "Content-Type: application/json" \\
                    --data-binary "@gs_ai_request.json" \\
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

                if (parsed.strategy != null) {
                    aiStrategy = parsed.strategy.toString().toLowerCase().trim()
                }
                if (parsed.confidence != null) {
                    try { aiConfidence = parsed.confidence.toString().toDouble() } catch (ignored) {}
                }
                parsed = null
            } catch (Exception e) {
                echo "[greenSchedule] Could not parse AI Agent response: ${e.message}"
                echo "[greenSchedule] Using AI fallback values."
                aiAgentReachable = false
            }
        } else {
            echo "[greenSchedule] ⚠️ Green AI Agent unreachable (curl exit ${curlStatus}). Using default strategy."
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
                preSelectedStrategy : aiStrategy,
                mlGreenProbability  : mlGreenProb,
                aiConfidence        : aiConfidence,
                reason              : "Developer override — scheduled for ${targetHour}:00"
            ]
        }
    }


    // ================================================================
    // FINAL SCHEDULING DECISION
    // ================================================================

    def mlWantsToSchedule = (mlAction == 'schedule')
    
    if (mlWantsToSchedule) {
        def delaySecs = _computeDelaySeconds(mlScheduledHour)
        def reason = "ML Optimizer recommends scheduling for ${mlScheduledHour}:00 (prob=${String.format('%.2f', mlGreenProb)})"
        
        echo '════════════════════════════════════════════'
        echo ' 🌿 GREEN SCHEDULING — RESCHEDULING'
        echo " ML Probability       : ${String.format('%.2f', mlGreenProb)}"
        echo " Schedule Decided By  : ML Optimizer → ${mlScheduledHour}:00"
        echo " Delay Time           : ${delaySecs} seconds"
        echo " Strategy Decided By  : AI Agent → ${aiStrategy} (conf=${String.format('%.2f', aiConfidence)})"
        echo " Reason               : ${reason}"
        echo '════════════════════════════════════════════'

        return [
            shouldSchedule      : true,
            scheduledHour       : mlScheduledHour,
            delaySeconds        : delaySecs,
            preSelectedStrategy : aiStrategy,
            mlGreenProbability  : mlGreenProb,
            aiConfidence        : aiConfidence,
            reason              : reason
        ]
    } else {
        def reason = 'ML Optimizer confirms a green window right now'
        
        echo '════════════════════════════════════════════'
        echo ' 🌿 GREEN SCHEDULING — EXECUTE NOW'
        echo " ML Probability       : ${String.format('%.2f', mlGreenProb)}"
        echo " Schedule Decided By  : ML Optimizer → Now"
        echo " Strategy Decided By  : AI Agent → ${aiStrategy} (conf=${String.format('%.2f', aiConfidence)})"
        echo " Reason               : ${reason}"
        echo '════════════════════════════════════════════'

        return [
            shouldSchedule      : false,
            scheduledHour       : 0,
            delaySeconds        : 0,
            preSelectedStrategy : aiStrategy,
            mlGreenProbability  : mlGreenProb,
            aiConfidence        : aiConfidence,
            reason              : reason
        ]
    }
}


// ================================================================
// PRIVATE HELPERS
// ================================================================

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
