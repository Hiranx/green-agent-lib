/**
 * greenCheck.groovy
 * -----------------
 * Global variable (shared library function) that any Jenkins pipeline
 * can call to consult the Green Deployment AI Agent before deploying.
 *
 * USAGE — add two lines to any existing Jenkinsfile:
 *
 *   @Library('green-agent') _          // top of file, loads the library
 *
 *   stage('Green AI Check') {
 *     steps { greenCheck() }           // that's it
 *   }
 *
 * OPTIONAL parameters (all have safe defaults):
 *
 *   greenCheck(
 *     agentUrl:         'http://172.17.0.1:5002',  // where your agent runs
 *     maxWaitHours:     6,                          // max hours to auto-wait
 *     checkIntervalMin: 30,                         // minutes between retries
 *   )
 *
 * WHAT IT DOES:
 *   1. Calls the Green Deployment AI Agent at agentUrl/api/check
 *   2. If agent says "deploy" → sets env.DEPLOY_STRATEGY and returns
 *   3. If agent says "wait"   → sleeps checkIntervalMin minutes, retries
 *   4. After maxWaitHours of waiting → fails the build with clear message
 *
 * SETS THESE ENV VARS for later stages:
 *   env.DEPLOY_STRATEGY  — "rolling" | "canary" | "recreate"
 *   env.CARBON_RATING    — "low" | "medium" | "high" | "very_high"
 *   env.AI_REASON        — human-readable explanation from the LLM
 *   env.AI_GREEN_SCORE   — numeric score 0-100
 *   env.AI_GREEN_GRADE   — "Excellent" | "Good" | "Moderate" | "Poor"
 *   env.AI_CO2_SAVING    — estimated CO2 saving percentage
 */
def call(Map config = [:]) {

    // ── Defaults — override any of these in your call ─────────────────────
    def agentUrl         = config.agentUrl         ?: env.GREEN_AGENT_URL ?: 'http://172.17.0.1:5002'
    def maxWaitHours     = (config.maxWaitHours     ?: 6) as int
    def checkIntervalMin = (config.checkIntervalMin ?: 30) as int
    def maxChecks        = (maxWaitHours * 60).intdiv(checkIntervalMin)

    // ── Plain String variables only — MUST NOT be maps or LazyMap ─────────
    // WHY: Jenkins CPS serializes all local variables to disk before sleep().
    // groovy.json.JsonSlurper returns LazyMap which is NOT Java-serializable.
    // Extracting as individual Strings before any sleep() is the only fix.
    def attempt       = 0
    def aiDecision    = 'deploy'
    def aiStrategy    = 'rolling'
    def aiReason      = ''
    def aiCarbon      = 'unknown'
    def aiGreenScore  = 'N/A'
    def aiGreenGrade  = 'N/A'
    def aiCo2Saving   = '0'
    def aiWindow      = ''

    def jobName     = env.JOB_NAME     ?: 'unknown'
    def buildNumber = env.BUILD_NUMBER ?: '?'

    // ── Fallback response used when agent is unreachable ──────────────────
    def fallback = '{"decision":"deploy","strategy":"rolling",' +
                   '"reason":"Agent unreachable - using safe default",' +
                   '"carbon_rating":"unknown","confidence":"0.5",' +
                   '"green_score":"N/A","green_grade":"N/A",' +
                   '"estimated_co2_saving_pct":"0","next_green_window":""}'

    // ── Auto-reschedule loop ───────────────────────────────────────────────
    while (aiDecision == 'wait' || attempt == 0) {
        attempt++

        echo "════════════════════════════════════════════"
        echo " 🌿 Green AI Check — Attempt ${attempt}/${maxChecks}"
        echo " Agent : ${agentUrl}"
        echo " Job   : ${jobName} #${buildNumber}"
        echo "════════════════════════════════════════════"

        // Call the agent — plain sh so no non-serializable objects are created
        def agentResponse = sh(
            script: """
                curl -sf -X POST ${agentUrl}/api/check \\
                    -H "Content-Type: application/json" \\
                    -d '{"job_name":"${jobName}","build_number":"${buildNumber}"}' \\
                || echo '${fallback}'
            """,
            returnStdout: true
        ).trim()

        // Parse then immediately extract every field as a plain String.
        // The parsed map is set to null before any sleep() to prevent
        // NotSerializableException during Jenkins CPS state serialization.
        def parsed    = new groovy.json.JsonSlurper().parseText(agentResponse)
        aiDecision    = (parsed.decision             ?: 'deploy').toString()
        aiStrategy    = (parsed.strategy             ?: 'rolling').toString()
        aiReason      = (parsed.reason               ?: '').toString()
        aiCarbon      = (parsed.carbon_rating        ?: 'unknown').toString()
        aiGreenScore  = (parsed.green_score  != null  ? parsed.green_score  : 'N/A').toString()
        aiGreenGrade  = (parsed.green_grade          ?: 'N/A').toString()
        aiCo2Saving   = (parsed.estimated_co2_saving_pct != null ? parsed.estimated_co2_saving_pct : '0').toString()
        aiWindow      = (parsed.next_green_window    ?: '').toString()
        parsed        = null   // discard — cannot survive sleep()

        // ── Print decision clearly in Jenkins console ──────────────────────
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

        if (aiDecision == 'wait') {
            // Exceeded max wait — fail with actionable message
            if (attempt >= maxChecks) {
                error("""
⚠️  Green AI Agent recommended waiting for ${maxWaitHours} hours straight.
Last reason   : ${aiReason}
Carbon Rating : ${aiCarbon}
Green Score   : ${aiGreenScore}/100 (${aiGreenGrade})
Next Window   : ${aiWindow ?: 'unknown'}
Action        : Review carbon conditions at ${agentUrl}/api/tools/carbon
                then re-trigger this build manually.
""")
            }

            // Sleep and retry
            def displayWindow = aiWindow ?: 'unknown'
            echo "⏳ Waiting for green window: ${displayWindow}"
            echo "   Sleeping ${checkIntervalMin} min then re-checking..."
            echo "   (Attempt ${attempt}/${maxChecks} | Max wait: ${maxWaitHours}h)"
            sleep time: checkIntervalMin, unit: 'MINUTES'
        }
    }

    // ── Green window confirmed — set env vars for later stages ────────────
    env.DEPLOY_STRATEGY = aiStrategy
    env.CARBON_RATING   = aiCarbon
    env.AI_REASON       = aiReason
    env.AI_GREEN_SCORE  = aiGreenScore
    env.AI_GREEN_GRADE  = aiGreenGrade
    env.AI_CO2_SAVING   = aiCo2Saving

    echo "✅ Green window confirmed after ${attempt} check(s)."
    echo "   Strategy : ${aiStrategy}"
    echo "   Carbon   : ${aiCarbon} | Score: ${aiGreenScore}/100 (${aiGreenGrade})"
}
