/**
 * notifyEnd.groovy
 * ----------------
 * Notifies Component 1 that a deployment has finished.
 *
 * USAGE (in post block):
 *   post {
 *     success { notifyEnd(status: 'SUCCESS') }
 *     failure { notifyEnd(status: 'FAILURE') }
 *   }
 *
 * OPTIONAL parameters:
 *   notifyEnd(
 *     metricsUrl:    'http://172.17.0.1:5001',
 *     status:        'SUCCESS',   // or 'FAILURE'
 *     carbonProfile: 'low_gradual',
 *     image:         'hiranx/demo-app:42',
 *   )
 */
def call(Map config = [:]) {

    def metricsUrl = config.metricsUrl ?: env.METRICS_URL     ?: 'http://172.17.0.1:5001'
    def status     = config.status     ?: 'SUCCESS'
    def strategy   = config.strategy   ?: env.DEPLOY_STRATEGY ?: 'rolling'
    def buildNumber= env.BUILD_NUMBER  ?: '?'
    def jobName    = env.JOB_NAME      ?: 'unknown'

    // Map strategy to carbon profile if not explicitly provided
    def profileMap = [
        'canary'  : 'medium_transient',
        'rolling' : 'low_gradual',
        'recreate': 'low_burst',
    ]
    def carbonProfile = config.carbonProfile ?: profileMap.get(strategy, 'low_gradual')
    def image         = config.image         ?: ''

    echo "📡 Notifying Component 1: deployment ${status}"

    def imageClause = image ? ""","image":"${image}"""" : ''

    sh """
        curl -s -X POST ${metricsUrl}/deployment/end \\
            -H "Content-Type: application/json" \\
            -d '{"status":"${status}","build_number":"${buildNumber}","strategy":"${strategy}","carbon_profile":"${carbonProfile}"${imageClause}}'
    """
}
