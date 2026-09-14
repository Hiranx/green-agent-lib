/**
 * greenCheck.groovy
 *
 * Jenkins Shared Library:
 *     greenCheck()
 *
 * Green Deployment AI Agent check.
 *
 * Behaviour: unchanged from the previous version. Only the console output
 * has been redesigned — minimal, colourful, and readable.
 *
 * Log shape (three stages):
 *
 *   1. REQUEST — one line, plus endpoint
 *   2. RESPONSE — compact decision card with confidence bar
 *   3. CONFIRM — one line summary
 *
 * Emoji indicators:
 *   Strategy   🔄 rolling    🐤 canary    ♻️  recreate
 *   Carbon     🟢 low        🟡 medium    🟠 high    🔴 very_high
 *   Grade      🏆 Excellent  ✅ Good      🟡 Moderate 🔴 Poor
 *   Agent      ✅ connected  ⚠️  unreachable
 *
 * Parameters:
 *   singleShot (boolean, default false)
 *     false: full polling loop, sleeping checkIntervalMin between retries.
 *     true:  exactly ONE call to the agent. No sleep. No retry.
 */

def call(Map config = [:]) {

    // ================================================================
    // URGENT DEPLOYMENT BYPASS
    // ================================================================

    if (env.URGENT_DEPLOY == 'true') {
        echo "⚡ Green AI bypassed  ·  urgent deployment  ·  rolling"
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
    def aiCarbonVal  = ''
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
        carbon_intensity_gco2_kwh: '',
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

        // ── REQUEST HEADER ───────────────────────────────────────────
        echo ""
        echo "🌿  GREEN AI  ·  ${jobName} #${buildNumber}  ·  attempt ${attempt}/${maxChecks}"
        echo "    ↳  ${agentUrl}/api/check"


        // ============================================================
        // REQUEST / RESPONSE FILES
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

        writeFile(file: requestFile, text: requestPayload)


        // ============================================================
        // REMOVE OLD RESPONSE
        // ============================================================

        sh(script: 'rm -f green_ai_response.json')


        // ============================================================
        // CALL GREEN AI AGENT
        // ============================================================

        def curlStatus = 1

        withEnv(["GREEN_AGENT_URL=${agentUrl}"]) {

            curlStatus = sh(
                script: '''
                    set +e

                    rm -f green_ai_response.json

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

                    if [ "$EXIT_CODE" -ne 0 ]; then
                        exit "$EXIT_CODE"
                    fi

                    if [ ! -s green_ai_response.json ]; then
                        exit 4
                    fi

                    exit 0
                ''',
                returnStatus: true
            )
        }


        // ================================================================
        // HANDLE AGENT FAILURE
        // ================================================================

        if (curlStatus != 0) {
            echo "    ⚠️  agent unreachable (curl ${curlStatus})  ·  using safe defaults"
            writeFile(file: responseFile, text: fallbackJson)
        }


        // ================================================================
        // READ RESPONSE
        // ================================================================

        def agentResponse = ''

        try {
            agentResponse = readFile(responseFile).trim()
        } catch (Exception e) {
            agentResponse = fallbackJson
        }

        if (!agentResponse) {
            agentResponse = fallbackJson
        }


        // ================================================================
        // RESET VALUES BEFORE PARSING
        // ================================================================

        aiDecision   = 'deploy'
        aiStrategy   = 'rolling'
        aiConfidence = 0.0d
        aiReason     = 'Agent response not parsed'
        aiCarbon     = 'unknown'
        aiCarbonVal  = ''
        aiGreenScore = 'N/A'
        aiGreenGrade = 'N/A'
        aiCo2Saving  = '0'
        aiWindow     = ''


        // ================================================================
        // PARSE JSON
        // ================================================================

        try {

            def parsed = new groovy.json.JsonSlurper().parseText(agentResponse)

            if (parsed.decision   != null) aiDecision   = parsed.decision.toString()
            if (parsed.strategy   != null) aiStrategy   = parsed.strategy.toString()
            if (parsed.reason     != null) aiReason     = parsed.reason.toString()
            if (parsed.carbon_rating != null) aiCarbon  = parsed.carbon_rating.toString()

            if (parsed.carbon_intensity_gco2_kwh != null) {
                aiCarbonVal = parsed.carbon_intensity_gco2_kwh.toString()
            }

            if (parsed.confidence != null) {
                try { aiConfidence = parsed.confidence.toString().toDouble() } catch (ignored) {}
            }

            if (parsed.green_score != null) aiGreenScore = parsed.green_score.toString()
            if (parsed.green_grade != null) aiGreenGrade = parsed.green_grade.toString()

            if (parsed.estimated_co2_saving_pct != null) {
                aiCo2Saving = parsed.estimated_co2_saving_pct.toString()
            }

            if (parsed.next_green_window != null) {
                aiWindow = parsed.next_green_window.toString()
            }

            parsed = null

        } catch (Exception e) {
            aiDecision   = 'deploy'
            aiStrategy   = 'rolling'
            aiReason     = 'Invalid AI response - using safe default'
            aiCarbon     = 'unknown'
            aiCarbonVal  = ''
            aiGreenScore = 'N/A'
            aiGreenGrade = 'N/A'
            aiCo2Saving  = '0'
            aiWindow     = ''
        }


        // ================================================================
        // NORMALIZE
        // ================================================================

        aiDecision = aiDecision.toLowerCase().trim()
        aiStrategy = aiStrategy.toLowerCase().trim()


        // ================================================================
        // VALIDATE
        // ================================================================

        if (!(aiStrategy in ['rolling', 'canary', 'recreate'])) {
            aiStrategy = 'rolling'
        }

        if (!(aiDecision in ['deploy', 'wait'])) {
            aiDecision = 'deploy'
            aiStrategy = 'rolling'
            aiReason   = "Unknown AI decision '${aiDecision}' - safe fallback"
        }


        // ================================================================
        // RESPONSE CARD
        // ================================================================

        def stratEmoji  = [rolling:'🔄', canary:'🐤', recreate:'♻️'].get(aiStrategy, '❓')
        def carbonEmoji = [low:'🟢', medium:'🟡', high:'🟠',
                           very_high:'🔴', unknown:'⚪'].get(aiCarbon, '⚪')
        def gradeEmoji  = [Excellent:'🏆', Good:'✅',
                           Moderate:'🟡', Poor:'🔴'].get(aiGreenGrade, '⚪')
        def agentLabel  = curlStatus == 0 ? '✅ CONNECTED'
                                          : '⚠️  UNREACHABLE'

        def barWidth = 20
        def filled   = Math.round(aiConfidence * barWidth).toInteger()
        def bar      = '█' * filled + '░' * (barWidth - filled)
        def confPct  = (aiConfidence * 100).round(0).toInteger()

        def carbonLine = aiCarbon
        if (aiCarbonVal) {
            carbonLine = "${aiCarbon}  ·  ${aiCarbonVal} gCO2/kWh"
        }

        echo ""
        echo "──────────────────────────────────────────────────────────"
        echo "  🌿  GREEN AI DECISION  ·  ${agentLabel}"
        echo "──────────────────────────────────────────────────────────"
        echo "      ${stratEmoji}   Strategy       ${aiStrategy}"
        echo "      ${carbonEmoji}   Carbon         ${carbonLine}"
        echo "      ${gradeEmoji}   Green Score    ${aiGreenScore}/100  ·  ${aiGreenGrade}"
        echo "      🎯   Confidence     ${confPct}%  ${bar}"
        if (aiWindow?.trim()) {
            echo "      🕐   Next window    ${aiWindow}"
        }
        echo "──────────────────────────────────────────────────────────"

        // Word-wrap the reason at ~54 chars, indent with 6 spaces.
        def reasonLines = _wrap(aiReason, 54)
        reasonLines.eachWithIndex { line, idx ->
            if (idx == 0) echo "      💬   ${line}"
            else          echo "           ${line}"
        }

        echo "──────────────────────────────────────────────────────────"
        echo ""


        // ================================================================
        // DEPLOY
        // ================================================================

        if (aiDecision == 'deploy') {

            env.DEPLOY_STRATEGY = aiStrategy.toString()
            env.CARBON_RATING   = aiCarbon.toString()
            env.AI_REASON       = aiReason.toString()
            env.AI_GREEN_SCORE  = aiGreenScore.toString()
            env.AI_GREEN_GRADE  = aiGreenGrade.toString()
            env.AI_CO2_SAVING   = aiCo2Saving.toString()

            echo "🌿  DEPLOY via ${aiStrategy}  ·  score ${aiGreenScore}/100 ${aiGreenGrade}"

            return aiStrategy.toString()
        }


        // ================================================================
        // WAIT
        // ================================================================

        if (aiDecision == 'wait') {

            // ── SINGLE-SHOT MODE ──────────────────────────────────────
            if (singleShot) {
                env.DEPLOY_STRATEGY = aiStrategy.toString()
                env.CARBON_RATING   = aiCarbon.toString()
                env.AI_REASON       = aiReason.toString()
                env.AI_GREEN_SCORE  = aiGreenScore.toString()
                env.AI_GREEN_GRADE  = aiGreenGrade.toString()
                env.AI_CO2_SAVING   = aiCo2Saving.toString()

                echo "⏳  WAIT recommended  ·  next window ${aiWindow ?: 'unknown'}  ·  returning ${aiStrategy}"

                return aiStrategy.toString()
            }

            // ── POLLING MODE (original behaviour) ────────────────────
            if (attempt >= maxChecks) {
                error("""
⚠️  Green AI recommended waiting for ${maxWaitHours} hours straight.

   Reason      : ${aiReason}
   Green Score : ${aiGreenScore}/100 (${aiGreenGrade})
   Next Window : ${aiWindow ?: 'unknown'}

   Inspect current carbon at:
     ${agentUrl}/api/tools/carbon

   Re-trigger this build manually when conditions improve.
""")
            }

            echo "⏳  WAIT  ·  next window ${aiWindow ?: 'unknown'}  ·  sleeping ${checkIntervalMin}m  (attempt ${attempt}/${maxChecks})"

            sleep(time: checkIntervalMin, unit: 'MINUTES')
            continue
        }


        // ================================================================
        // FINAL SAFETY FALLBACK
        // ================================================================

        env.DEPLOY_STRATEGY = 'rolling'
        env.CARBON_RATING   = aiCarbon.toString()
        env.AI_REASON       = 'Safe fallback - rolling deployment'
        env.AI_GREEN_SCORE  = aiGreenScore.toString()
        env.AI_GREEN_GRADE  = aiGreenGrade.toString()
        env.AI_CO2_SAVING   = aiCo2Saving.toString()

        echo "🌿  DEPLOY via rolling  ·  final safety fallback"

        return 'rolling'
    }
}


// ================================================================
// PRIVATE HELPERS
// ================================================================

/**
 * Word-wrap a string into lines of at most `width` characters.
 * Words longer than `width` are placed on their own line.
 */
private static List<String> _wrap(String text, int width) {
    def result = []
    def words  = (text ?: '').trim().split(/\s+/)

    def line = ''
    words.each { w ->
        def candidate = line ? "${line} ${w}" : w
        if (candidate.length() > width) {
            if (line) result << line
            line = w
        } else {
            line = candidate
        }
    }
    if (line) result << line
    if (result.isEmpty()) result << ''

    return result
}
