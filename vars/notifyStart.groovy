/**
 * notifyStart.groovy
 * ------------------
 * Notifies Component 1 (deployment tracker) that a deployment has started.
 *
 * USAGE:
 *   stage('Notify Start') {
 *     steps { notifyStart() }
 *   }
 *
 * OPTIONAL parameters:
 *   notifyStart(
 *     metricsUrl:   'http://172.17.0.1:5001',  // Component 1 URL
 *     strategy:     'rolling',                  // override strategy
 *     canaryWeight: '20',                       // canary traffic %
 *   )
 *
 * READS these env vars automatically (set by greenCheck() or your pipeline):
 *   env.DEPLOY_STRATEGY  — set by greenCheck()
 *   env.METRICS_URL      — set in your pipeline environment block
 *   env.CANARY_WEIGHT    — set in your pipeline environment block
 */
def call(Map config = [:]) {

    def metricsUrl   = config.metricsUrl   ?: env.METRICS_URL     ?: 'http://172.17.0.1:5001'
    def strategy     = config.strategy     ?: env.DEPLOY_STRATEGY ?: 'rolling'
    def canaryWeight = config.canaryWeight ?: env.CANARY_WEIGHT   ?: '0'
    def jobName      = env.JOB_NAME        ?: 'unknown'
    def buildNumber  = env.BUILD_NUMBER    ?: '?'

    echo "📡 Notifying Component 1: deployment started"
    echo "   Job: ${jobName} #${buildNumber} | Strategy: ${strategy}"

    sh """
        curl -s -X POST ${metricsUrl}/deployment/start \\
            -H "Content-Type: application/json" \\
            -d '{"job_name":"${jobName}","build_number":"${buildNumber}","strategy":"${strategy}","canary_weight":"${canaryWeight}"}'
    """
}
