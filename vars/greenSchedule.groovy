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
 *   mlGreenProbability   — double: raw ML optimizer signal
 *   aiConfidence         — double: raw AI agent signal
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
    def mlScheduledHourStr = (config.scheduledHour    ?: env.SCHEDULED_HOUR    ?: '0').toString().trim()
    def mlGreenProbStr     = (config.greenProbability ?: env.GREEN_PROBABILITY  ?: '0.5').toString().trim()

    def mlScheduledHour = mlScheduledHourStr.isInteger() ? mlScheduledHourStr.toInteger() : 0
    def mlGreenProb     = 0.5d
    try { mlGreenProb = mlGreenProbStr.toDouble() } catch (ignored) {}

    def mlGreenPct  = String.format('%.1f', mlGreenProb * 100)
    def mlDecision  = (mlAction == 'schedule') ? 'RESCHEDULE' : 'EXECUTE NOW'
    def mlEmoji     = (mlAction == 'schedule') ? '⏸️ ' : '🚀'

    // ================================================================
    // OPENING SYSTEM BANNER
    // ================================================================

    echo ''
    echo '╔══════════════════════════════════════════════════════════╗'
    echo '║      🌱  GREEN DEPLOYMENT SCHEDULING SYSTEM  🌱         ║'
    echo '╠══════════════════════════════════════════════════════════╣'
    echo "║  Job          : ${jobName.take(44).padRight(44)}║"
    echo "║  Build        : #${buildNumber.take(43).padRight(43)}║"
    echo "║  Agent URL    : ${agentUrl.take(44).padRight(44)}║"
    echo "║  Urgent Mode  : ${(urgentDeploy ? 'YES — green checks bypassed' : 'NO — full green check active').padRight(44)}║"
    echo '╠══════════════════════════════════════════════════════════╣'
    echo '║  TWO-MODEL ARCHITECTURE:                                 ║'
    echo '║  ┌─ Model 1: ML Optimizer (LightGBM)                     ║'
    echo '║  │   Decides WHEN to deploy                               ║'
    echo '║  └─ Model 2: AI Agent (Ollama LLM / ReAct)               ║'
    echo '║      Decides HOW to deploy (strategy)                     ║'
    echo '╚══════════════════════════════════════════════════════════╝'
    echo ''


    // ================================================================
    // URGENT BYPASS
    // ================================================================

    if (urgentDeploy) {

        echo ''
        echo '╔══════════════════════════════════════════════════════════╗'
        echo '║  ⚡  URGENT DEPLOYMENT MODE ACTIVATED                    ║'
        echo '╠══════════════════════════════════════════════════════════╣'
        echo '║  Green scheduling bypassed by URGENT_DEPLOY=true         ║'
        echo '║  Strategy: rolling (safe default)                        ║'
        echo '║  All carbon checks skipped — deploying immediately       ║'
        echo '╚══════════════════════════════════════════════════════════╝'
        echo ''

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
    // MODEL 1 — ML OPTIMIZER RESULT BANNER
    // ================================================================

    def mlConfidenceBar = _buildBar(mlGreenProb, 20)

    echo ''
    echo '╔══════════════════════════════════════════════════════════╗'
    echo '║   📊  MODEL 1: ML OPTIMIZER (LightGBM) RESULT           ║'
    echo '╠══════════════════════════════════════════════════════════╣'
    echo "║  Input Action         : ${mlAction.padRight(35)}║"
    echo "║  ${mlEmoji} Decision            : ${mlDecision.padRight(35)}║"
    echo "║  🎯 Green Probability  : ${mlGreenPct}%  ${mlConfidenceBar.padRight(26)}║"
    if (mlAction == 'schedule') {
        echo "║  🕐 Scheduled Hour     : ${mlScheduledHour}:00  (next occurrence)              ║"
    }
    echo '╠══════════════════════════════════════════════════════════╣'
    echo '║  ML Model: LightGBM trained on carbon intensity          ║'
    echo '║  Features: lag/rolling-window carbon signals             ║'
    echo '║  Metric:   F1=0.979 | AUC-ROC=0.999 (5-fold CV)         ║'
    echo '╚══════════════════════════════════════════════════════════╝'
    echo ''


    // ================================================================
    // MODEL 2 — GREEN AI AGENT STRATEGY QUERY
    // ================================================================

    echo ''
    echo '╔══════════════════════════════════════════════════════════╗'
    echo '║   🤖  MODEL 2: GREEN AI AGENT REQUEST                    ║'
    echo '╠══════════════════════════════════════════════════════════╣'
    echo "║  Endpoint   : ${agentUrl}/api/check".take(61).padRight(61) + '║'
    echo '║  Model      : Ollama qwen2.5:1.5b (local LLM)            ║'
    echo '║  Pattern    : ReAct (Reasoning + Acting)                  ║'
    echo '║  Tools      : carbon_intensity, system_resources,         ║'
    echo '║               time_context, deployment_history,           ║'
    echo '║               next_green_window                           ║'
    echo '╠══════════════════════════════════════════════════════════╣'
    echo '║  Sending request... (timeout: 180s)                       ║'
    echo '╚══════════════════════════════════════════════════════════╝'
    echo ''

    def aiStrategy       = 'rolling'
    def aiConfidence     = 0.5d
    def aiGreenScore     = 'N/A'
    def aiGreenGrade     = 'N/A'
    def aiCarbon         = 'unknown'
    def aiReason         = 'Not available'
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
                if (parsed.green_score != null) {
                    aiGreenScore = parsed.green_score.toString()
                }
                if (parsed.green_grade != null) {
                    aiGreenGrade = parsed.green_grade.toString()
                }
                if (parsed.carbon_rating != null) {
                    aiCarbon = parsed.carbon_rating.toString()
                }
                if (parsed.reason != null) {
                    aiReason = parsed.reason.toString()
                }
                parsed = null

            } catch (Exception e) {
                echo "[greenSchedule] Could not parse AI Agent response: ${e.message}"
                aiAgentReachable = false
            }
        }
    }

    // Validate strategy
    if (!(aiStrategy in ['rolling', 'canary', 'recreate'])) {
        aiStrategy = 'rolling'
    }

    // ── AI Agent response banner ──────────────────────────────────────────

    def carbonEmoji  = aiCarbon == 'low' ? '🟢' : aiCarbon == 'medium' ? '🟡' : aiCarbon == 'high' ? '🟠' : '🔴'
    def gradeEmoji   = aiGreenGrade == 'Excellent' ? '🏆' : aiGreenGrade == 'Good' ? '✅' : aiGreenGrade == 'Moderate' ? '🟡' : '🔴'
    def stratEmoji   = aiStrategy == 'canary' ? '🐤' : aiStrategy == 'recreate' ? '♻️ ' : '🔄'
    def agentStatus  = aiAgentReachable ? '✅ CONNECTED' : '⚠️  UNREACHABLE (using defaults)'
    def confBar      = _buildBar(aiConfidence, 20)
    def confPct      = String.format('%.1f', aiConfidence * 100)

    echo ''
    echo '╔══════════════════════════════════════════════════════════╗'
    echo '║   🤖  MODEL 2: AI AGENT RESPONSE                        ║'
    echo '╠══════════════════════════════════════════════════════════╣'
    echo "║  Agent Status   : ${agentStatus.padRight(44)}║"
    echo "║  ${stratEmoji} Strategy       : ${aiStrategy.padRight(44)}║"
    echo "║  ${carbonEmoji} Carbon Rating  : ${aiCarbon.padRight(44)}║"
    echo "║  ${gradeEmoji} Green Score    : ${(aiGreenScore + '/100  (' + aiGreenGrade + ')').padRight(44)}║"
    echo "║  🎯 Confidence   : ${confPct}%  ${confBar.padRight(26)}║"
    echo '╠══════════════════════════════════════════════════════════╣'
    echo "║  💬 Reason:                                              ║"

    def reasonWords = aiReason.split(' ')
    def rLine = '║     '
    reasonWords.each { word ->
        if ((rLine + word).length() > 59) {
            echo (rLine.padRight(61) + '║')
            rLine = '║     ' + word + ' '
        } else {
            rLine = rLine + word + ' '
        }
    }
    if (rLine.trim() != '║') {
        echo (rLine.padRight(61) + '║')
    }

    echo '╚══════════════════════════════════════════════════════════╝'
    echo ''


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

            echo ''
            echo '╔══════════════════════════════════════════════════════════╗'
            echo '║   🛠️   DEVELOPER OVERRIDE ACTIVE                        ║'
            echo '╠══════════════════════════════════════════════════════════╣'
            echo "║  Override Hour    : ${targetHour}:00                                    ║"
            echo "║  Delay            : ${delaySecs}s (${(delaySecs/3600).toInteger()}h until target)              ║"
            echo "║  Strategy (AI)    : ${aiStrategy.padRight(44)}║"
            echo '╠══════════════════════════════════════════════════════════╣'
            echo '║  ⏸️  Scheduling as requested by developer override        ║'
            echo '╚══════════════════════════════════════════════════════════╝'
            echo ''

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
    // COMBINED FINAL DECISION BANNER
    // ================================================================

    def mlWantsToSchedule = (mlAction == 'schedule')
    def delaySecs = mlWantsToSchedule ? _computeDelaySeconds(mlScheduledHour) : 0
    def delayHours = (delaySecs / 3600).toInteger()
    def delayMins  = ((delaySecs % 3600) / 60).toInteger()

    echo ''
    echo '╔══════════════════════════════════════════════════════════╗'
    echo '║      🌿  COMBINED GREEN SCHEDULING DECISION  🌿         ║'
    echo '╠══════════════════════════════════════════════════════════╣'
    echo '║                                                          ║'
    echo '║  ┌─────────────────────────────────────────────────┐    ║'
    echo "║  │  📊 ML Optimizer  →  WHEN                       │    ║"
    echo "║  │     Decision    : ${mlDecision.padRight(32)}│    ║"
    echo "║  │     Probability : ${mlGreenPct}%  ${mlConfidenceBar.padRight(21)}│    ║"
    if (mlWantsToSchedule) {
        echo "║  │     Target Hour : ${mlScheduledHour}:00                              │    ║"
        echo "║  │     Wait Time   : ${delayHours}h ${delayMins}m                            │    ║"
    } else {
        echo "║  │     Action      : Deploy immediately                 │    ║"
    }
    echo '║  └─────────────────────────────────────────────────┘    ║'
    echo '║                                                          ║'
    echo '║  ┌─────────────────────────────────────────────────┐    ║'
    echo "║  │  🤖 AI Agent      →  HOW                        │    ║"
    echo "║  │     Strategy    : ${stratEmoji} ${aiStrategy.padRight(30)}│    ║"
    echo "║  │     Carbon      : ${carbonEmoji} ${aiCarbon.padRight(30)}│    ║"
    echo "║  │     Green Score : ${(aiGreenScore + '/100 (' + aiGreenGrade + ')').padRight(31)}│    ║"
    echo "║  │     Confidence  : ${confPct}%  ${confBar.take(19).padRight(19)}│    ║"
    echo '║  └─────────────────────────────────────────────────┘    ║'
    echo '║                                                          ║'
    echo '╠══════════════════════════════════════════════════════════╣'

    if (mlWantsToSchedule) {
        echo "║  ${mlEmoji} FINAL: RESCHEDULING to ${mlScheduledHour}:00  (${delayHours}h ${delayMins}m from now)        ║"
        echo "║  ${stratEmoji}  When deployed, will use: ${aiStrategy}                  ║"
    } else {
        echo '║  🚀 FINAL: EXECUTE NOW — conditions are green            ║'
        echo "║  ${stratEmoji}  Strategy selected: ${aiStrategy.padRight(38)}║"
    }

    echo '╚══════════════════════════════════════════════════════════╝'
    echo ''

    if (mlWantsToSchedule) {
        def reason = "ML Optimizer recommends scheduling for ${mlScheduledHour}:00 (prob=${String.format('%.2f', mlGreenProb)})"
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
        return [
            shouldSchedule      : false,
            scheduledHour       : 0,
            delaySeconds        : 0,
            preSelectedStrategy : aiStrategy,
            mlGreenProbability  : mlGreenProb,
            aiConfidence        : aiConfidence,
            reason              : 'ML Optimizer confirms a green window right now'
        ]
    }
}


// ================================================================
// PRIVATE HELPERS
// ================================================================

private int _computeDelaySeconds(int targetHour) {
    def hoursToWait = targetHour - new Date().getHours()
    if (hoursToWait <= 0) {
        hoursToWait += 24
    }
    return hoursToWait * 3600
}

private String _buildBar(double value, int width) {
    def filled = Math.round(value * width).toInteger()
    def empty  = width - filled
    return '[' + ('█' * filled) + ('░' * empty) + ']'
}
