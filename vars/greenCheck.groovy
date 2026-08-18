/**
 * greenCheck.groovy
 *
 * Green Deployment AI Agent integration for Jenkins.
 *
 * Sets:
 *   env.DEPLOY_STRATEGY
 *   env.CARBON_RATING
 *   env.AI_REASON
 *   env.AI_GREEN_SCORE
 *   env.AI_GREEN_GRADE
 *   env.AI_CO2_SAVING
 */

def call(Map config = [:]) {

    // ================================================================
    // 1. URGENT DEPLOYMENT BYPASS
    // ================================================================
    if (env.URGENT_DEPLOY == 'true') {
        echo "⚡ Urgent deployment — skipping Green AI Check"

        env.DEPLOY_STRATEGY = 'rolling'
        env.CARBON_RATING   = 'skipped'
        env.AI_GREEN_SCORE  = '0'
        env.AI_GREEN_GRADE  = 'Skipped'
        env.AI_REASON       = 'Urgent deployment - green check bypassed'
        env.AI_CO2_SAVING   = '0'

        return
    }

    // ================================================================
    // 2. CONFIGURATION
    // ================================================================
    def agentUrl = config.agentUrl ?:
                   env.GREEN_AGENT_URL ?:
                   'http://172.17.0.1:5002'

    def maxWaitHours =
        (config.maxWaitHours ?: 6) as int

    def checkIntervalMin =
        (config.checkIntervalMin ?: 30) as int

    def maxChecks =
        Math.max(1, (maxWaitHours * 60).intdiv(checkIntervalMin))

    // ================================================================
    // 3. IMPORTANT:
    // Do NOT rely on the Jenkinsfile default strategy.
    // Start empty so a failed AI strategy propagation is detected.
    // ================================================================
    env.DEPLOY_STRATEGY = ''

    def attempt      = 0
    def aiDecision   = 'deploy'
    def aiStrategy   = ''
    def aiReason     = ''
    def aiCarbon     = 'unknown'
    def aiGreenScore = 'N/A'
    def aiGreenGrade = 'N/A'
    def aiCo2Saving  = '0'
    def aiWindow     = ''

    def jobName =
        (env.JOB_NAME ?: 'unknown').replaceAll('"', '\\"')

    def buildNumber =
        (env.BUILD_NUMBER ?: '?').replaceAll('"', '\\"')

    // ================================================================
    // 4. FALLBACK
    // ================================================================
    //
    // IMPORTANT:
    // Do NOT silently use rolling if the AI agent is unavailable.
    // For a research/demo pipeline, failing is safer than using the
    // wrong deployment strategy.
    //
    def fallback = '''
{
    "decision": "error",
    "strategy": "",
    "reason": "Green AI Agent unreachable"
}
'''.trim()

    // ================================================================
    // 5. AI CHECK LOOP
    // ================================================================
    while (true) {

        attempt++

        echo "════════════════════════════════════════════"
        echo " 🌿 Green AI Check — Attempt ${attempt}/${maxChecks}"
        echo " Agent : ${agentUrl}"
        echo " Job   : ${jobName} #${buildNumber}"
        echo "════════════════════════════════════════════"

        // ------------------------------------------------------------
        // Call AI agent
        // ------------------------------------------------------------
        def agentResponse = sh(
            script: """
                set -e

                RESPONSE=\\\$(curl -sf -X POST "${agentUrl}/api/check" \\
                    -H "Content-Type: application/json" \\
                    -d '{"job_name":"${jobName}","build_number":"${buildNumber}"}' \\
                    || true)

                if [ -z "\\\$RESPONSE" ]; then
                    echo '${fallback}'
                else
                    echo "\\\$RESPONSE"
                fi
            """,
            returnStdout: true
        ).trim()

        echo "[GREEN AI] Raw response received."

        // ------------------------------------------------------------
        // Parse JSON
        // ------------------------------------------------------------
        def parsed

        try {
            parsed = new groovy.json.JsonSlurper().parseText(agentResponse)
        } catch (Exception e) {
            error("""
❌ Green AI Agent returned invalid JSON.

Response:
${agentResponse}

Error:
${e.message}
""")
        }

        // ------------------------------------------------------------
        // Extract values as Strings BEFORE sleep()
        // ------------------------------------------------------------
        aiDecision =
            (parsed.decision ?: 'error').toString().trim().toLowerCase()

        aiStrategy =
            (parsed.strategy ?: '').toString().trim().toLowerCase()

        aiReason =
            (parsed.reason ?: '').toString()

        aiCarbon =
            (parsed.carbon_rating ?: 'unknown').toString()

        aiGreenScore =
            (parsed.green_score != null
                ? parsed.green_score
                : 'N/A').toString()

        aiGreenGrade =
            (parsed.green_grade ?: 'N/A').toString()

        aiCo2Saving =
            (parsed.estimated_co2_saving_pct != null
                ? parsed.estimated_co2_saving_pct
                : '0').toString()

        aiWindow =
            (parsed.next_green_window ?: '').toString()

        // Important for Jenkins CPS
        parsed = null

        // ============================================================
        // 6. VALIDATE AI DECISION
        // ============================================================

        def validStrategies = [
            'rolling',
            'canary',
            'recreate'
        ]

        if (aiDecision != 'deploy' && aiDecision != 'wait') {

            error("""
❌ Green AI Agent returned an invalid decision.

Decision : ${aiDecision}
Strategy : ${aiStrategy}
Reason   : ${aiReason}

Expected decision:
  deploy
  wait
""")
        }

        // ============================================================
        // 7. VALIDATE STRATEGY
        // ============================================================

        if (!(aiStrategy in validStrategies)) {

            error("""
❌ Green AI Agent returned an invalid deployment strategy.

AI Strategy : '${aiStrategy}'

Allowed strategies:
  rolling
  canary
  recreate

Reason:
${aiReason}
""")
        }

        // ============================================================
        // 8. DISPLAY AI DECISION
        // ============================================================

        echo "════════════════════════════════════════════"
        echo " AI GREEN DEPLOYMENT DECISION"
        echo "════════════════════════════════════════════"
        echo " Decision        : ${aiDecision.toUpperCase()}"
        echo " Strategy        : ${aiStrategy}"
        echo " Carbon Rating   : ${aiCarbon}"
        echo " Green Score     : ${aiGreenScore}/100 (${aiGreenGrade})"
        echo " CO2 Saving Est. : ~${aiCo2Saving}%"
        echo " Reason          : ${aiReason}"

        if (aiWindow) {
            echo " Next Green Window: ${aiWindow}"
        }

        echo "════════════════════════════════════════════"

        // ============================================================
        // 9. WAIT
        // ============================================================

        if (aiDecision == 'wait') {

            if (attempt >= maxChecks) {

                error("""
⚠️ Green AI Agent recommended waiting for ${maxWaitHours} hours.

Last reason   : ${aiReason}
Carbon Rating : ${aiCarbon}
Green Score   : ${aiGreenScore}/100 (${aiGreenGrade})
Next Window   : ${aiWindow ?: 'unknown'}

Action:
Review carbon conditions and re-trigger the build.
""")
            }

            echo "⏳ Waiting for green window: ${aiWindow ?: 'unknown'}"
            echo "   Sleeping ${checkIntervalMin} min then re-checking..."
            echo "   (Attempt ${attempt}/${maxChecks} | Max wait: ${maxWaitHours}h)"

            sleep time: checkIntervalMin, unit: 'MINUTES'

            continue
        }

        // ============================================================
        // 10. DEPLOY DECISION
        // ============================================================

        if (aiDecision == 'deploy') {
            break
        }
    }

    // ================================================================
    // 11. APPLY AI STRATEGY
    // ================================================================

    env.DEPLOY_STRATEGY = aiStrategy

    env.CARBON_RATING  = aiCarbon
    env.AI_REASON      = aiReason
    env.AI_GREEN_SCORE = aiGreenScore
    env.AI_GREEN_GRADE = aiGreenGrade
    env.AI_CO2_SAVING  = aiCo2Saving

    // ================================================================
    // 12. FINAL VALIDATION
    // ================================================================

    if (!(env.DEPLOY_STRATEGY in ['rolling', 'canary', 'recreate'])) {
        error("""
❌ Deployment strategy was not correctly propagated.

AI Strategy       : ${aiStrategy}
Jenkins Strategy  : ${env.DEPLOY_STRATEGY}

Deployment stopped to prevent using the wrong strategy.
""")
    }

    // ================================================================
    // 13. CONFIRMATION
    // ================================================================

    echo "════════════════════════════════════════════"
    echo "✅ GREEN WINDOW CONFIRMED"
    echo "════════════════════════════════════════════"
    echo " Checks completed : ${attempt}"
    echo " AI Decision      : ${aiDecision}"
    echo " AI Strategy      : ${aiStrategy}"
    echo " Jenkins Strategy : ${env.DEPLOY_STRATEGY}"
    echo " Carbon           : ${aiCarbon}"
    echo " Green Score      : ${aiGreenScore}/100 (${aiGreenGrade})"
    echo " CO2 Saving       : ~${aiCo2Saving}%"
    echo "════════════════════════════════════════════"
}
