/**
 * greenCheck.groovy
 *
 * Jenkins Shared Library:
 *     greenCheck()
 *
 * Green Deployment AI Agent check.
 *
 * Behavior:
 *   1. Calls the Green AI Agent.
 *   2. Sends JSON using a request file.
 *   3. Never embeds JSON directly inside curl.
 *   4. Never uses Groovy GString interpolation inside shell scripts.
 *   5. Safely falls back to DEPLOY + ROLLING if the agent is unavailable.
 *   6. Supports DEPLOY / WAIT decisions.
 *   7. Validates the returned strategy.
 *   8. Stores AI values in Jenkins env variables.
 *   9. Returns the selected strategy to the Jenkinsfile.
 *
 * Parameters:
 *   singleShot (boolean, default false)
 *     When false (default): runs the full polling loop, sleeping checkIntervalMin
 *     minutes between retries, up to maxWaitHours. Original behaviour.
 *
 *     When true: makes exactly ONE call to the agent and returns immediately.
 *     If the agent says 'wait', the strategy is still returned — the caller
 *     (greenSchedule or Confirm Deploy Strategy stage) handles timing, not this
 *     function. No sleep. No retry. Useful for pre-flight checks and fast
 *     sanity checks at deploy time without holding a Jenkins executor.
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

    // Remove trailing slash so:
    // http://172.17.0.1:5002/
    // becomes:
    // http://172.17.0.1:5002

    agentUrl = agentUrl.toString().replaceAll('/+$', '')

    def maxWaitHours =
        (config.maxWaitHours ?: 6) as int

    def checkIntervalMin =
        (config.checkIntervalMin ?: 30) as int

    if (checkIntervalMin <= 0) {
        error("Green AI checkIntervalMin must be greater than 0")
    }

    if (maxWaitHours <= 0) {
        error("Green AI maxWaitHours must be greater than 0")
    }

    def singleShot = config.singleShot == true

    def maxChecks = singleShot
        ? 1
        : Math.max(1, (maxWaitHours * 60).intdiv(checkIntervalMin))


    // ================================================================
    // VARIABLES
    // ================================================================

    def attempt      = 0

    def aiDecision   = 'deploy'
    def aiStrategy   = 'rolling'
    def aiConfidence = 0.5d
    def aiReason     = ''
    def aiCarbon     = 'unknown'
    def aiGreenScore = 'N/A'
    def aiGreenGrade = 'N/A'
    def aiCo2Saving  = '0'
    def aiWindow     = ''

    def jobName =
        (env.JOB_NAME ?: 'unknown').toString()

    def buildNumber =
        (env.BUILD_NUMBER ?: '?').toString()


    // ================================================================
    // FALLBACK RESPONSE
    // ================================================================

    def fallbackJson = groovy.json.JsonOutput.toJson([
        decision: 'deploy',
        strategy: 'rolling',
        reason: 'Agent unreachable - using safe default',
        carbon_rating: 'unknown',
        confidence: '0.0',
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

        echo ''
        echo '╔══════════════════════════════════════════════════════════╗'
        echo '║   🤖  GREEN AI AGENT REQUEST                             ║'
        echo '╠══════════════════════════════════════════════════════════╣'
        echo "║  Attempt    : ${attempt}/${maxChecks}".take(61).padRight(61) + '║'
        echo "║  Endpoint   : ${agentUrl}/api/check".take(61).padRight(61) + '║'
        echo "║  Job        : ${jobName} #${buildNumber}".take(61).padRight(61) + '║'
        echo '╚══════════════════════════════════════════════════════════╝'
        echo ''


        // ============================================================
        // REQUEST / RESPONSE FILES
        //
        // Use fixed filenames.
        //
        // This avoids:
        //   - Groovy interpolation problems
        //   - shell variable problems
        //   - Jenkins workspace path problems
        // ============================================================

        def requestFile  = 'green_ai_request.json'
        def responseFile = 'green_ai_response.json'


        // ============================================================
        // CREATE JSON PAYLOAD
        // ============================================================

        def requestPayload = groovy.json.JsonOutput.toJson([
            job_name: jobName,
            build_number: buildNumber
        ])

        writeFile(
            file: requestFile,
            text: requestPayload
        )

        echo "📄 Green AI request created"


        // ============================================================
        // SHOW REQUEST FOR DEBUGGING
        //
        // Safe because this contains only job/build information.
        // ============================================================

        echo "Green AI request:"
        echo requestPayload


        // ============================================================
        // REMOVE OLD RESPONSE
        // ============================================================

        sh(
            script: '''
                rm -f green_ai_response.json
            '''
        )


        // ============================================================
        // CALL GREEN AI AGENT
        //
        // IMPORTANT:
        //
        // Use SINGLE-QUOTED Groovy string:
        //
        //     '''
        //
        // NOT:
        //
        //     """
        //
        // This prevents Groovy from interpreting shell $ variables.
        // ============================================================

        // ============================================================
        // CALL GREEN AI AGENT
        // ============================================================
        
        def curlStatus = 1
        
        withEnv([
            "GREEN_AGENT_URL=${agentUrl}"
        ]) {
        
            curlStatus = sh(
                script: '''
                    set +e
        
                    rm -f green_ai_response.json
        
                    echo "[GREEN AI] Calling agent..."
                    echo "[GREEN AI] URL: ${GREEN_AGENT_URL}/api/check"
                    echo "[GREEN AI] Waiting for AI response..."
        
                    curl -sS -f \
                        --connect-timeout 10 \
                        --max-time 180 \
                        --retry 0 \
                        -X POST \
                        "${GREEN_AGENT_URL}/api/check" \
                        -H "Content-Type: application/json" \
                        --data-binary "@green_ai_request.json" \
                        -o green_ai_response.json
        
                    EXIT_CODE=$?
        
                    echo "[GREEN AI] curl exit code: ${EXIT_CODE}"
        
                    if [ "$EXIT_CODE" -ne 0 ]; then
                        echo "[GREEN AI] Agent request failed."
                        exit "$EXIT_CODE"
                    fi
        
                    if [ ! -s green_ai_response.json ]; then
                        echo "[GREEN AI] Agent returned empty response."
                        exit 4
                    fi
        
                    echo "[GREEN AI] Agent response received."
        
                    exit 0
                ''',
                returnStatus: true
            )
        }

        // ================================================================
        // HANDLE AGENT FAILURE
        // ================================================================

        if (curlStatus != 0) {

            echo "⚠️ Green AI Agent unreachable."
            echo "   Agent URL : ${agentUrl}"
            echo "   curl code : ${curlStatus}"
            echo "   Using safe fallback: DEPLOY + ROLLING"

            writeFile(
                file: responseFile,
                text: fallbackJson
            )

        } else {

            echo "✅ Green AI Agent responded successfully"

        }


        // ================================================================
        // READ RESPONSE
        // ================================================================

        def agentResponse = ''

        try {

            agentResponse =
                readFile(responseFile).trim()

        } catch (Exception e) {

            echo "⚠️ Could not read Green AI response file."
            echo "   Error: ${e.message}"
            echo "   Using safe fallback."

            agentResponse = fallbackJson
        }


        // ================================================================
        // EMPTY RESPONSE SAFETY
        // ================================================================

        if (!agentResponse) {

            echo "⚠️ Green AI returned an empty response."
            echo "   Using safe fallback: DEPLOY + ROLLING"

            agentResponse = fallbackJson
        }


        // ================================================================
        // DISPLAY RAW RESPONSE
        // ================================================================

        echo "Green AI response:"
        echo agentResponse


        // ================================================================
        // RESET VALUES BEFORE PARSING
        // ================================================================

        aiDecision   = 'deploy'
        aiStrategy   = 'rolling'
        aiConfidence = 0.0d
        aiReason     = 'Agent response not parsed'
        aiCarbon     = 'unknown'
        aiGreenScore = 'N/A'
        aiGreenGrade = 'N/A'
        aiCo2Saving  = '0'
        aiWindow     = ''


        // ================================================================
        // PARSE JSON
        // ================================================================

        try {

            def parsed =
                new groovy.json.JsonSlurper().parseText(agentResponse)


            if (parsed.decision != null) {
                aiDecision =
                    parsed.decision.toString()
            }

            if (parsed.strategy != null) {
                aiStrategy =
                    parsed.strategy.toString()
            }

            if (parsed.confidence != null) {
                try { aiConfidence = parsed.confidence.toString().toDouble() } catch (ignored) {}
            }

            if (parsed.reason != null) {
                aiReason =
                    parsed.reason.toString()
            }

            if (parsed.carbon_rating != null) {
                aiCarbon =
                    parsed.carbon_rating.toString()
            }

            if (parsed.green_score != null) {
                aiGreenScore =
                    parsed.green_score.toString()
            }

            if (parsed.green_grade != null) {
                aiGreenGrade =
                    parsed.green_grade.toString()
            }

            if (parsed.estimated_co2_saving_pct != null) {
                aiCo2Saving =
                    parsed.estimated_co2_saving_pct.toString()
            }

            if (parsed.next_green_window != null) {
                aiWindow =
                    parsed.next_green_window.toString()
            }

            // Prevent LazyMap from surviving CPS suspension.
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

            def invalidDecision = aiDecision

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

        def carbonEmoji  = aiCarbon == 'low' ? '🟢' : aiCarbon == 'medium' ? '🟡' : aiCarbon == 'high' ? '🟠' : '🔴'
        def gradeEmoji   = aiGreenGrade == 'Excellent' ? '🏆' : aiGreenGrade == 'Good' ? '✅' : aiGreenGrade == 'Moderate' ? '🟡' : '🔴'
        def stratEmoji   = aiStrategy == 'canary' ? '🐤' : aiStrategy == 'recreate' ? '♻️ ' : '🔄'
        def agentStatus  = curlStatus == 0 ? '✅ CONNECTED' : '⚠️  UNREACHABLE (using defaults)'
        def confBar      = _buildBar(aiConfidence, 20)
        def confPct      = String.format('%.1f', aiConfidence * 100)

        echo ''
        echo '╔══════════════════════════════════════════════════════════╗'
        echo '║   🤖  GREEN AI AGENT RESPONSE                            ║'
        echo '╠══════════════════════════════════════════════════════════╣'
        echo "║  Agent Status   : ${agentStatus.padRight(44)}║"
        echo "║  ${stratEmoji} Strategy       : ${aiStrategy.padRight(44)}║"
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
        // DEPLOY
        // ================================================================

        if (aiDecision == 'deploy') {

            // ------------------------------------------------------------
            // Persist values in Jenkins environment
            // ------------------------------------------------------------
            echo "DEBUG: aiStrategy before assignment = ${aiStrategy}"
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
            echo "   Score    : ${env.AI_GREEN_SCORE}/100 (${env.AI_GREEN_GRADE})"

            echo "🌿 AI selected deployment strategy: ${env.DEPLOY_STRATEGY}"

            // ------------------------------------------------------------
            // IMPORTANT
            // Return String directly to Jenkinsfile.
            // ------------------------------------------------------------

            return aiStrategy.toString()
        }


        // ================================================================
        // WAIT
        // ================================================================

        if (aiDecision == 'wait') {

            // ── SINGLE-SHOT MODE ──────────────────────────────────────
            // When singleShot=true the caller (greenSchedule / Confirm
            // Deploy Strategy) handles timing. We return the strategy
            // immediately without sleeping or looping.
            if (singleShot) {
                echo "⏳ [Single-shot] Agent recommends waiting. Returning strategy '${aiStrategy}' for caller to handle."
                echo "   Next window : ${aiWindow ?: 'unknown'}"
                echo "   Reason      : ${aiReason}"

                env.DEPLOY_STRATEGY = aiStrategy.toString()
                env.CARBON_RATING   = aiCarbon.toString()
                env.AI_REASON       = aiReason.toString()
                env.AI_GREEN_SCORE  = aiGreenScore.toString()
                env.AI_GREEN_GRADE  = aiGreenGrade.toString()
                env.AI_CO2_SAVING   = aiCo2Saving.toString()

                return aiStrategy.toString()
            }

            // ── POLLING MODE (original behaviour) ────────────────────
            if (attempt >= maxChecks) {

                error("""
⚠️ Green AI Agent recommended waiting for ${maxWaitHours} hours straight.

Last reason   : ${aiReason}
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


            // ------------------------------------------------------------
            // Sleep
            // ------------------------------------------------------------

            sleep(
                time: checkIntervalMin,
                unit: 'MINUTES'
            )

            continue
        }


        // ================================================================
        // FINAL SAFETY FALLBACK
        // ================================================================

        echo "⚠️ Final safety fallback: DEPLOY + ROLLING"

        env.DEPLOY_STRATEGY = 'rolling'
        env.CARBON_RATING   = aiCarbon.toString()
        env.AI_REASON       = 'Safe fallback - rolling deployment'
        env.AI_GREEN_SCORE  = aiGreenScore.toString()
        env.AI_GREEN_GRADE  = aiGreenGrade.toString()
        env.AI_CO2_SAVING   = aiCo2Saving.toString()

        echo "🌿 AI selected deployment strategy: rolling"

        return 'rolling'
    }
}

// ================================================================
// PRIVATE HELPERS
// ================================================================

private String _buildBar(double value, int width) {
    def filled = Math.round(value * width).toInteger()
    def empty  = width - filled
    return '[' + ('█' * filled) + ('░' * empty) + ']'
}
