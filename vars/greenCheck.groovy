/**
 * greenCheck.groovy
 *
 * Jenkins Shared Library:
 *   greenCheck()
 *
 * Checks the Green Deployment AI Agent before deployment.
 *
 * IMPORTANT:
 * - Returns the selected deployment strategy as a String.
 * - Also writes deployment-related values into env.
 * - JSON is written to a file and passed to curl using @file.
 *   This avoids shell quoting / JSON parsing problems.
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

        // IMPORTANT:
        // Return the strategy as well as setting env.
        return 'rolling'
    }


    // ================================================================
    // CONFIGURATION
    // ================================================================

    def agentUrl = config.agentUrl ?:
                   env.GREEN_AGENT_URL ?:
                   'http://172.17.0.1:5002'

    def maxWaitHours =
        (config.maxWaitHours ?: 6) as int

    def checkIntervalMin =
        (config.checkIntervalMin ?: 30) as int

    // Prevent division by zero / zero attempts
    def maxChecks = Math.max(
        1,
        (maxWaitHours * 60).intdiv(checkIntervalMin)
    )


    // ================================================================
    // SIMPLE SERIALIZABLE VARIABLES
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

    def jobName =
        env.JOB_NAME ?: 'unknown'

    def buildNumber =
        env.BUILD_NUMBER ?: '?'


    // ================================================================
    // FALLBACK JSON
    // ================================================================

    def fallbackJson = groovy.json.JsonOutput.toJson([
        decision: 'deploy',
        strategy: 'rolling',
        reason: 'Agent unreachable - using safe default',
        carbon_rating: 'unknown',
        confidence: '0.5',
        green_score: 'N/A',
        green_grade: 'N/A',
        estimated_co2_saving_pct: '0',
        next_green_window: ''
    ])


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
        // CREATE JSON PAYLOAD SAFELY
        //
        // DO NOT put JSON directly into the curl command.
        // ============================================================

        def requestPayload = groovy.json.JsonOutput.toJson([
            job_name: jobName,
            build_number: buildNumber.toString()
        ])

        def requestFile =
            "green_ai_request_${attempt}.json"

        def responseFile =
            "green_ai_response_${attempt}.json"

        writeFile(
            file: requestFile,
            text: requestPayload
        )


        // ============================================================
        // CALL AI AGENT
        // ============================================================

        def curlStatus = 1

        withEnv([
            "GREEN_AGENT_URL=${agentUrl}"
        ]) {

            curlStatus = sh(
                script: '''
                    set +e

                    rm -f green_ai_response.json

                    curl -sS -f \
                        --connect-timeout 10 \
                        --max-time 30 \
                        -X POST \
                        "${GREEN_AGENT_URL}/api/check" \
                        -H "Content-Type: application/json" \
                        --data @green_ai_request.json \
                        -o green_ai_response.json

                    EXIT_CODE=$?

                    if [ "$EXIT_CODE" -ne 0 ]; then
                        echo "curl failed with exit code: $EXIT_CODE"
                        exit "$EXIT_CODE"
                    fi

                    if [ ! -s green_ai_response.json ]; then
                        echo "AI Agent returned an empty response"
                        exit 4
                    fi

                    cp green_ai_response.json "${WORKSPACE}/green_ai_response_current.json"

                    exit 0
                ''',
                returnStatus: true
            )
        }


        // ============================================================
        // HANDLE AGENT FAILURE
        // ============================================================

        if (curlStatus != 0) {

            echo "⚠️ Green AI Agent unreachable."
            echo "   Using safe fallback: DEPLOY + ROLLING"

            writeFile(
                file: responseFile,
                text: fallbackJson
            )

        } else {

            sh """
                cp "${WORKSPACE}/green_ai_response_current.json" "${responseFile}"
            """
        }


        // ============================================================
        // READ RESPONSE
        // ============================================================

        def agentResponse =
            readFile(responseFile).trim()

        if (!agentResponse) {

            echo "⚠️ Empty response from Green AI Agent."
            echo "   Using safe fallback."

            agentResponse = fallbackJson
        }


        // ============================================================
        // PARSE JSON
        // ============================================================

        try {

            def parsed =
                new groovy.json.JsonSlurper().parseText(agentResponse)

            aiDecision =
                (parsed.decision ?: 'deploy').toString()

            aiStrategy =
                (parsed.strategy ?: 'rolling').toString()

            aiReason =
                (parsed.reason ?: '').toString()

            aiCarbon =
                (parsed.carbon_rating ?: 'unknown').toString()

            aiGreenScore =
                (
                    parsed.green_score != null
                        ? parsed.green_score
                        : 'N/A'
                ).toString()

            aiGreenGrade =
                (parsed.green_grade ?: 'N/A').toString()

            aiCo2Saving =
                (
                    parsed.estimated_co2_saving_pct != null
                        ? parsed.estimated_co2_saving_pct
                        : '0'
                ).toString()

            aiWindow =
                (parsed.next_green_window ?: '').toString()

            // Do not allow LazyMap to survive CPS sleep.
            parsed = null

        } catch (Exception e) {

            echo "⚠️ Could not parse Green AI response."
            echo "   Response: ${agentResponse}"
            echo "   Error: ${e.message}"
            echo "   Using safe fallback: DEPLOY + ROLLING"

            aiDecision   = 'deploy'
            aiStrategy   = 'rolling'
            aiReason     = 'Invalid AI response - using safe default'
            aiCarbon     = 'unknown'
            aiGreenScore = 'N/A'
            aiGreenGrade = 'N/A'
            aiCo2Saving  = '0'
            aiWindow     = ''
        }


        // ================================================================
        // NORMALIZE
        // ================================================================

        aiDecision =
            aiDecision.toLowerCase().trim()

        aiStrategy =
            aiStrategy.toLowerCase().trim()


        // ================================================================
        // VALIDATE STRATEGY
        // ================================================================

        if (!(aiStrategy in ['rolling', 'canary', 'recreate'])) {

            echo "⚠️ Invalid strategy returned by AI: ${aiStrategy}"
            echo "   Falling back to rolling."

            aiStrategy = 'rolling'
        }


        // ================================================================
        // VALIDATE DECISION
        // ================================================================

        if (!(aiDecision in ['deploy', 'wait'])) {

            echo "⚠️ Unknown AI decision returned: ${aiDecision}"
            echo "   Falling back to DEPLOY + ROLLING."

            aiDecision = 'deploy'
            aiStrategy = 'rolling'
            aiReason =
                "Unknown AI decision '${aiDecision}' - safe fallback"
        }


        // ================================================================
        // DISPLAY RESULT
        // ================================================================

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

            // ============================================================
            // Persist all values in Jenkins environment
            // ============================================================

            env.DEPLOY_STRATEGY =
                aiStrategy.toString()

            env.CARBON_RATING =
                aiCarbon.toString()

            env.AI_REASON =
                aiReason.toString()

            env.AI_GREEN_SCORE =
                aiGreenScore.toString()

            env.AI_GREEN_GRADE =
                aiGreenGrade.toString()

            env.AI_CO2_SAVING =
                aiCo2Saving.toString()


            echo "✅ Green window confirmed after ${attempt} check(s)."
            echo "   Strategy : ${env.DEPLOY_STRATEGY}"
            echo "   Carbon   : ${env.CARBON_RATING}"
            echo "   Score    : ${env.AI_GREEN_SCORE}/100 (${env.AI_GREEN_GRADE})"

            // ============================================================
            // CRITICAL FIX:
            // Return strategy directly to Jenkinsfile.
            // ============================================================

            return aiStrategy.toString()
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


            // ============================================================
            // Sleep
            // ============================================================

            sleep(
                time: checkIntervalMin,
                unit: 'MINUTES'
            )

            continue
        }


        // ================================================================
        // FINAL SAFETY FALLBACK
        // ================================================================

        echo "⚠️ Falling back to safe deployment strategy: ROLLING"

        env.DEPLOY_STRATEGY = 'rolling'
        env.CARBON_RATING   = aiCarbon
        env.AI_REASON       =
            "Safe fallback - rolling deployment"
        env.AI_GREEN_SCORE  = aiGreenScore
        env.AI_GREEN_GRADE  = aiGreenGrade
        env.AI_CO2_SAVING   = aiCo2Saving

        return 'rolling'
    }
}
