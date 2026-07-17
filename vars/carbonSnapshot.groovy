/**
 * carbonSnapshot.groovy
 * ---------------------
 * Records a carbon snapshot for a deployment phase in Component 1.
 *
 * USAGE:
 *   carbonSnapshot(phase: 'before')
 *   carbonSnapshot(phase: 'during', infraMultiplier: '1.1')
 *   carbonSnapshot(phase: 'after',  downtimeSeconds: '20')
 *   carbonSnapshot(phase: 'canary_live', infraMultiplier: '1.2', canaryWeight: '20')
 *
 * REQUIRED:
 *   phase — one of: before | during | after | canary_live | promoted
 */
def call(Map config = [:]) {

    def metricsUrl      = config.metricsUrl      ?: env.METRICS_URL     ?: 'http://172.17.0.1:5001'
    def phase           = config.phase           ?: 'before'
    def strategy        = config.strategy        ?: env.DEPLOY_STRATEGY ?: 'rolling'
    def buildNumber     = env.BUILD_NUMBER       ?: '?'
    def infraMultiplier = config.infraMultiplier ?: '1.0'
    def downtimeSeconds = config.downtimeSeconds ?: ''
    def canaryWeight    = config.canaryWeight    ?: ''
    def note            = config.note            ?: ''

    echo "📸 Carbon snapshot: phase=${phase} | strategy=${strategy} | infra=${infraMultiplier}x"

    def extras = ''
    if (downtimeSeconds) extras += ""","downtime_seconds":"${downtimeSeconds}""""
    if (canaryWeight)    extras += ""","canary_weight":"${canaryWeight}""""
    if (note)            extras += ""","note":"${note}""""

    sh """
        curl -s -X POST ${metricsUrl}/carbon/snapshot \\
            -H "Content-Type: application/json" \\
            -d '{"phase":"${phase}","strategy":"${strategy}","build_number":"${buildNumber}","infra_multiplier":${infraMultiplier}${extras}}'
    """
}
