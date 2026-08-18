/**
 * greenCheck.groovy
 *
 * Jenkins Shared Library:
 *   greenCheck()
 *
 * Checks the Green Deployment AI Agent before deployment.
 */

def call(Map config = [:]) {

    // ================================================================
    // URGENT DEPLOYMENT BYPASS
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
    // CONFIGURATION
    // ================================================================

    def agentUrl = config.agentUrl ?:
                   env.GREEN_AGENT_URL ?:
                   'http://172.17.0.1:5002'

    def maxWaitHours = (config.maxWaitHours ?: 6) as int

    def checkIntervalMin = (config.checkIntervalMin ?: 30) as int

    // Prevent division by zero / zero attempts
    def maxChecks = Math.max(
        1,
        (maxWaitHours * 60).intdiv(checkIntervalMin)
    )


    // ================================================================
    // ONLY SIMPLE / SERIALIZABLE VARIABLES
    // ================================================================

    def attempt      = 0
    def aiDecision   = 'deploy'
    def aiStrategy   = 'rolling'
    def aiReason     = ''
    def aiCarbon     = 'unknown'
    def aiGreenScore = 'N/A'
    def aiGreenGrade = 'N/A'
    def aiCo2Saving  = '0'
    def aiWindow     = ''

    def jobName = env.JOB_NAME ?: 'unknown'
    def buildNumber = env.BUILD_NUMBER ?: '?'


    // ================================================================
    // FALLBACK JSON
    // ================================================================

    def fallbackJson =
        '{"decision":"deploy",' +
        '"strategy":"rolling",' +
        '"reason":"Agent unreachable - using safe default",' +
        '"carbon_rating":"unknown",' +
        '"confidence":"0.5",' +
        '"green_score":"N/A",' +
        '"green_grade":"N/A",' +
        '"estimated_co2_saving_pct":"0",' +
        '"next_green_window":""}'


    // ================================================================
    // GREEN AI CHECK LOOP
    // ================================================================

    while (true) {

        attempt++

        echo "════════════════════════════════════════════"
        echo " 🌿 Green AI Check — Attempt ${attempt}/${maxChecks}"
        echo " Agent : ${agentUrl}"
        echo " Job   : ${jobName} #${buildNumber}"
        echo "════════════════════════════════════════════"


        // ============================================================
        // CALL AI AGENT
        //
        // IMPORTANT:
        // Do NOT inject JSON directly into single-quoted shell strings.
        // Pass values through environment variables instead.
        // ============================================================

        def responseFile = "green_ai_response_${attempt}.json"

        withEnv([
            "GREEN_AGENT_URL=${agentUrl}",
            "GREEN_JOB_NAME=${jobName}",
            "GREEN_BUILD_NUMBER=${buildNumber}"
        ]) {

            def curlStatus = sh(
                script: '''
                    set +e

                    curl -sS -f \
                        -X POST "${GREEN_AGENT_URL}/api/check" \
                        -H "Content-Type: application/json" \
                        --data "{\"job_name\":\"${GREEN_JOB_NAME}\",\"build_number\":\"${GREEN_BUILD_NUMBER}\"}" \
                        > "${WORKSPACE}/green_ai_response.json"

                    EXIT_CODE=$?

                    if [ "$EXIT_CODE" -ne 0 ]; then
                        exit "$EXIT_CODE"
                    fi

                    exit 0
                ''',
                returnStatus: true
            )


            if (curlStatus != 0) {

                echo "⚠️ Green AI Agent unreachable."
                echo "   Using safe fallback: DEPLOY + ROLLING"

                writeFile(
                    file: responseFile,
                    text: fallbackJson
                )

            } else {

                // Copy response to the expected attempt-specific file
                sh """
                    cp "${WORKSPACE}/green_ai_response.json" "${responseFile}"
                """
            }
        }


        // ============================================================
        // READ RESPONSE
        // ============================================================

        def agentResponse = readFile(responseFile).trim()

        if (!agentResponse) {
            echo "⚠️ Empty response from Green AI Agent."
            agentResponse = fallbackJson
        }


        // ============================================================
        // PARSE JSON
        // ============================================================

        try {

            def parsed =
                new groovy.json.JsonSlurper().parseText(agentResponse)

            // Extract EVERYTHING as String before any sleep()
            aiDecision =
                (parsed.decision ?: 'deploy').toString()

            aiStrategy =
                (parsed.strategy ?: 'rolling').toString()

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

            // IMPORTANT:
            // Do not keep LazyMap alive across sleep()
            parsed = null

        } catch (Exception e) {

            echo "⚠️ Could not parse Green AI response."
            echo "   Response: ${agentResponse}"
            echo "   Error: ${e.message}"

            // Safe fallback
            aiDecision   = 'deploy'
            aiStrategy   = 'rolling'
            aiReason     = 'Invalid AI response - using safe default'
            aiCarbon     = 'unknown'
            aiGreenScore = 'N/A'
            aiGreenGrade = 'N/A'
            aiCo2Saving  = '0'
            aiWindow     = ''
        }


        // ============================================================
        // NORMALIZE DECISION
        // ============================================================

        aiDecision = aiDecision.toLowerCase().trim()
        aiStrategy = aiStrategy.toLowerCase().trim()


        // ============================================================
        // VALIDATE STRATEGY
        // ============================================================

        if (!(aiStrategy in ['rolling', 'canary', 'recreate'])) {

            echo "⚠️ Invalid strategy returned by AI: ${aiStrategy}"
            echo "   Falling back to rolling."

            aiStrategy = 'rolling'
        }


        // ============================================================
        // DISPLAY RESULT
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

        if (aiWindow?.trim()) {
            echo " Next Green Window: ${aiWindow}"
        }

        echo "════════════════════════════════════════════"


        // ================================================================
        // DEPLOY DECISION
        // ================================================================

        if (aiDecision == 'deploy') {

            env.DEPLOY_STRATEGY = aiStrategy
            env.CARBON_RATING   = aiCarbon
            env.AI_REASON       = aiReason
            env.AI_GREEN_SCORE  = aiGreenScore
            env.AI_GREEN_GRADE  = aiGreenGrade
            env.AI_CO2_SAVING   = aiCo2Saving

            echo "✅ Green window confirmed after ${attempt} check(s)."
            echo "   Strategy : ${aiStrategy}"
            echo "   Carbon   : ${aiCarbon}"
            echo "   Score    : ${aiGreenScore}/100 (${aiGreenGrade})"

            return
        }


        // ================================================================
        // WAIT DECISION
        // ================================================================

        if (aiDecision == 'wait') {

            if (attempt >= maxChecks) {

                error("""
⚠️ Green AI Agent recommended waiting for ${maxWaitHours} hours straight.

Last reason   : ${aiReason}
Carbon Rating : ${aiCarbon}
Green Score   : ${aiGreenScore}/100 (${aiGreenGrade})
Next Window   : ${aiWindow ?: 'unknown'}

Action:
Review carbon conditions at:

${agentUrl}/api/tools/carbon

Then re-trigger this build manually.
""")
            }


            def displayWindow =
                aiWindow?.trim()
                    ? aiWindow
                    : 'unknown'

            echo "⏳ Waiting for green window: ${displayWindow}"
            echo "   Sleeping ${checkIntervalMin} minutes..."
            echo "   Attempt ${attempt}/${maxChecks}"
            echo "   Maximum wait: ${maxWaitHours} hours"


            // ========================================================
            // IMPORTANT:
            // Everything that survives this sleep is a String/int.
            // No LazyMap survives Jenkins CPS serialization.
            // ========================================================

            sleep(
                time: checkIntervalMin,
                unit: 'MINUTES'
            )

            continue
        }


        // ================================================================
        // UNKNOWN DECISION
        // ================================================================

        echo "⚠️ Unknown AI decision: ${aiDecision}"
        echo "   Falling back to deploy using rolling strategy."

        env.DEPLOY_STRATEGY = 'rolling'
        env.CARBON_RATING   = aiCarbon
        env.AI_REASON       = "Unknown AI decision '${aiDecision}' - safe fallback"
        env.AI_GREEN_SCORE  = aiGreenScore
        env.AI_GREEN_GRADE  = aiGreenGrade
        env.AI_CO2_SAVING   = aiCo2Saving

        return
    }
}
