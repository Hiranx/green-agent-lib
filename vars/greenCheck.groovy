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
 * - Writes deployment values into env.
 * - Uses an ABSOLUTE Jenkins workspace path for JSON files.
 * - Uses curl --data-binary @FILE to avoid JSON/shell quoting issues.
 * - Does NOT incorrectly report curl file errors as "Agent unreachable".
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

        return 'rolling'
    }


    // ================================================================
    // CONFIGURATION
    // ================================================================

    def agentUrl = config.agentUrl ?:
                   env.GREEN_AGENT_URL ?:
                   'http://172.17.0.1:5002'

    // Remove trailing slash if supplied
    agentUrl = agentUrl.toString().replaceAll('/+$', '')

    def maxWaitHours =
        (config.maxWaitHours ?: 6) as int

    def checkIntervalMin =
        (config.checkIntervalMin ?: 30) as int

    def maxChecks = Math.max(
        1,
        (maxWaitHours * 60).intdiv(checkIntervalMin)
    )


    // ================================================================
    // VARIABLES
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
        reason: 'Green AI Agent unavailable - using safe default',
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
        // FILE NAMES
        // ============================================================

        def requestFile =
            "green_ai_request_${attempt}.json"

        def responseFile =
            "green_ai_response_${attempt}.json"


        // ============================================================
        // ABSOLUTE WORKSPACE PATHS
        //
        // THIS IS THE IMPORTANT FIX.
        // ============================================================

        def workspace =
            env.WORKSPACE ?: pwd()

        def requestPath =
            "${workspace}/${requestFile}"

        def responsePath =
            "${workspace}/${responseFile}"


        // ============================================================
        // CREATE JSON PAYLOAD
        // ============================================================

        def requestPayload =
            groovy.json.JsonOutput.toJson([
                job_name: jobName,
                build_number: buildNumber.toString()
            ])


        writeFile(
            file: requestFile,
            text: requestPayload
        )


        echo "📄 AI request file: ${requestPath}"


        // ============================================================
        // VERIFY REQUEST FILE EXISTS
        // ============================================================

        def requestExists =
            fileExists(requestFile)

        if (!requestExists) {

            echo "❌ AI request file was NOT created."
            echo "   Expected: ${requestPath}"
            echo "   Using safe fallback: DEPLOY + ROLLING"

            writeFile(
                file: responseFile,
                text: fallbackJson
            )

        } else {

            echo "✅ AI request file created successfully."

            // ========================================================
            // CALL AI AGENT
            // ========================================================

            def curlStatus = sh(
                script: """
                    set +e

                    echo "[GREEN AI] Workspace:"
                    pwd

                    echo "[GREEN AI] Request file:"
                    ls -l "${requestPath}"

                    echo "[GREEN AI] Request payload:"
                    cat "${requestPath}"

                    echo "[GREEN AI] Calling:"
                    echo "${agentUrl}/api/check"

                    rm -f "${responsePath}"

                    curl -sS -f \\
                        --connect-timeout 10 \\
                        --max-time 30 \\
                        -X POST \\
                        "${agentUrl}/api/check" \\
                        -H "Content-Type: application/json" \\
                        --data-binary "@${requestPath}" \\
                        -o "${responsePath}"

                    EXIT_CODE=\\$?

                    echo "[GREEN AI] curl exit code: \\${EXIT_CODE}"

                    if [ "\\${EXIT_CODE}" -ne 0 ]; then
                        exit "\\${EXIT_CODE}"
                    fi

                    if [ ! -f "${responsePath}" ]; then
                        echo "[GREEN AI] Response file was not created."
                        exit 4
                    fi

                    if [ ! -s "${responsePath}" ]; then
                        echo "[GREEN AI] Response file is empty."
                        exit 5
                    fi

                    echo "[GREEN AI] Response received:"
                    cat "${responsePath}"

                    exit 0
                """,
                returnStatus: true
            )


            // ========================================================
            // HANDLE CURL FAILURE
            // ========================================================

            if (curlStatus != 0) {

                echo "⚠️ Green AI request failed."
                echo "   curl exit code: ${curlStatus}"
                echo "   Agent URL      : ${agentUrl}/api/check"
                echo "   Request file   : ${requestPath}"

                if (curlStatus == 6) {
                    echo "   Cause: Could not resolve agent hostname."
                }

                if (curlStatus == 7) {
                    echo "   Cause: Could not connect to Green AI Agent."
                }

                if (curlStatus == 22) {
                    echo "   Cause: Green AI Agent returned an HTTP error."
                }

                if (curlStatus == 26) {
                    echo "   Cause: curl could not read the request file."
                    echo "   This should NOT happen with the absolute path fix."
                }

                if (curlStatus == 28) {
                    echo "   Cause: Green AI Agent request timed out."
                }

                echo "   Using safe fallback: DEPLOY + ROLLING"

                writeFile(
                    file: responseFile,
                    text: fallbackJson
                )
            }
        }


        // ================================================================
        // READ RESPONSE
        // ================================================================

        def agentResponse = ''

        try {

            agentResponse =
                readFile(responseFile).trim()

        } catch (Exception e) {

            echo "⚠️ Could not read AI response file."
            echo "   File: ${responseFile}"
            echo "   Error: ${e.message}"

            agentResponse =
                fallbackJson
        }


        // ================================================================
        // EMPTY RESPONSE
        // ================================================================

        if (!agentResponse) {

            echo "⚠️ Empty response from Green AI Agent."
            echo "   Using safe fallback."

            agentResponse =
                fallbackJson
        }


        // ================================================================
        // PARSE JSON
        // ================================================================

        try {

            def parsed =
                new groovy.json.JsonSlurper().parseText(agentResponse)


            aiDecision =
                (
                    parsed.decision != null
                        ? parsed.decision
                        : 'deploy'
                ).toString()


            aiStrategy =
                (
                    parsed.strategy != null
                        ? parsed.strategy
                        : 'rolling'
                ).toString()


            aiReason =
                (
                    parsed.reason != null
                        ? parsed.reason
                        : ''
                ).toString()


            aiCarbon =
                (
                    parsed.carbon_rating != null
                        ? parsed.carbon_rating
                        : 'unknown'
                ).toString()


            aiGreenScore =
                (
                    parsed.green_score != null
                        ? parsed.green_score
                        : 'N/A'
                ).toString()


            aiGreenGrade =
                (
                    parsed.green_grade != null
                        ? parsed.green_grade
                        : 'N/A'
                ).toString()


            aiCo2Saving =
                (
                    parsed.estimated_co2_saving_pct != null
                        ? parsed.estimated_co2_saving_pct
                        : '0'
                ).toString()


            aiWindow =
                (
                    parsed.next_green_window != null
                        ? parsed.next_green_window
                        : ''
                ).toString()


            // Prevent LazyMap from surviving CPS checkpoints
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

        if (!(aiStrategy in [
            'rolling',
            'canary',
            'recreate'
        ])) {

            echo "⚠️ Invalid strategy returned by AI: ${aiStrategy}"
            echo "   Falling back to rolling."

            aiStrategy = 'rolling'
        }


        // ================================================================
        // VALIDATE DECISION
        // ================================================================

        if (!(aiDecision in [
            'deploy',
            'wait'
        ])) {

            def invalidDecision =
                aiDecision

            echo "⚠️ Unknown AI decision returned: ${invalidDecision}"
            echo "   Falling back to DEPLOY + ROLLING."

            aiDecision = 'deploy'
            aiStrategy = 'rolling'

            aiReason =
                "Unknown AI decision '${invalidDecision}' - safe fallback"
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
        // DEPLOY
        // ================================================================

        if (aiDecision == 'deploy') {

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
            // RETURN STRATEGY TO JENKINSFILE
            // ============================================================

            return aiStrategy.toString()
        }


        // ================================================================
        // WAIT
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
        env.AI_REASON       = 'Safe fallback - rolling deployment'
        env.AI_GREEN_SCORE  = aiGreenScore
        env.AI_GREEN_GRADE  = aiGreenGrade
        env.AI_CO2_SAVING   = aiCo2Saving

        return 'rolling'
    }
}
