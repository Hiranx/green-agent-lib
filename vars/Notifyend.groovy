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
 *     status:        'SUCCESS',
 *     carbonProfile: 'low_gradual',
 *     image:         'hiranx/demo-app:42',
 *   )
 */
def call(Map config = [:]) {

    def metricsUrl  = config.metricsUrl    ?: env.METRICS_URL     ?: 'http://172.17.0.1:5001'
    def status      = config.status        ?: 'SUCCESS'
    def strategy    = config.strategy      ?: env.DEPLOY_STRATEGY ?: 'rolling'
    def buildNumber = env.BUILD_NUMBER     ?: '?'
    def image       = config.image         ?: ''

    def profileMap = [
        'canary'  : 'medium_transient',
        'rolling' : 'low_gradual',
        'recreate': 'low_burst',
    ]
    def carbonProfile = config.carbonProfile ?: profileMap.get(strategy, 'low_gradual')

    echo "Notifying Component 1: deployment ${status}"

    // Build body as a plain variable — avoids embedded triple-quote syntax errors
    def body
    if (image) {
        body = '{"status":"' + status + '","build_number":"' + buildNumber + '","strategy":"' + strategy + '","carbon_profile":"' + carbonProfile + '","image":"' + image + '"}'
    } else {
        body = '{"status":"' + status + '","build_number":"' + buildNumber + '","strategy":"' + strategy + '","carbon_profile":"' + carbonProfile + '"}'
    }

    sh "curl -s -X POST ${metricsUrl}/deployment/end -H 'Content-Type: application/json' -d '${body}'"
}
