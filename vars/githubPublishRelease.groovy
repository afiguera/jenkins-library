import com.sap.piper.Utils
import com.sap.piper.ConfigurationHelper

import groovy.transform.Field

@Field String STEP_NAME = 'githubPublishRelease'
@Field Set GENERAL_CONFIG_KEYS = ['githubApiUrl', 'githubTokenCredentialsId', 'githubServerUrl']
@Field Set STEP_CONFIG_KEYS = [
    'addClosedIssues',
    'addDeltaToLastRelease',
    'customFilterExtension',
    'excludeLabels',
    'githubApiUrl',
    'githubTokenCredentialsId',
    'githubOrg',
    'githubRepo',
    'githubServerUrl',
    'releaseBodyHeader',
    'version'
]
@Field Set PARAMETER_KEYS = STEP_CONFIG_KEYS

void call(Map parameters = [:]) {
    handlePipelineStepErrors(stepName: STEP_NAME, stepParameters: parameters) {
        def script = parameters.script ?: [commonPipelineEnvironment: commonPipelineEnvironment]

        // load default & individual configuration
        Map config = ConfigurationHelper
            .loadStepDefaults(this)
            .mixinGeneralConfig(script.commonPipelineEnvironment, GENERAL_CONFIG_KEYS)
            .mixinStepConfig(script.commonPipelineEnvironment, STEP_CONFIG_KEYS)
            .mixinStageConfig(script.commonPipelineEnvironment, parameters.stageName?:env.STAGE_NAME, STEP_CONFIG_KEYS)
            .mixin(parameters, PARAMETER_KEYS)
            .addIfEmpty('githubOrg', script.commonPipelineEnvironment.getGitFolder())
            .addIfEmpty('githubRepo', script.commonPipelineEnvironment.getGitRepo())
            .addIfEmpty('version', script.commonPipelineEnvironment.getArtifactVersion())
            .withMandatoryProperty('githubOrg')
            .withMandatoryProperty('githubRepo')
            .withMandatoryProperty('githubTokenCredentialsId')
            .withMandatoryProperty('version')
            .use()

        new Utils().pushToSWA([step: STEP_NAME], config)

        withCredentials([string(credentialsId: config.githubTokenCredentialsId, variable: 'TOKEN')]) {
            def releaseBody = config.releaseBodyHeader?"${config.releaseBodyHeader}<br />":''
            def content = getLastRelease(config, TOKEN)
            if (config.addClosedIssues)
                releaseBody += addClosedIssue(config, TOKEN, content.published_at)
            if (config.addDeltaToLastRelease)
                releaseBody += addDeltaToLastRelease(config, content.tag_name)
            postNewRelease(config, TOKEN, releaseBody)
        }
    }
}

Map getLastRelease(config, TOKEN){
    def result = [:]
    try {
        def response = httpRequest "${config.githubApiUrl}/repos/${config.githubOrg}/${config.githubRepo}/releases/latest?access_token=${TOKEN}"
        result = readJSON text: response.content
    } catch (e) {
        echo "[${STEP_NAME}] This is the first release - no previous releases available ${e.getMessage()}"
        config.addDeltaToLastRelease = false
        result = [
            published_at: '2017-06-21T15:11:57Z',
            tag_name: ''
        ]
    }
    return result
}

String addClosedIssue(config, TOKEN, publishedAt){
    if (config.customFilterExtension) {
        config.customFilterExtension = "&${config.customFilterExtension}"
    }

    def response = httpRequest "${config.githubApiUrl}/repos/${config.githubOrg}/${config.githubRepo}/issues?access_token=${TOKEN}&per_page=100&state=closed&direction=asc&since=${publishedAt}${config.customFilterExtension}"
    def result = ''

    content = readJSON text: response.content

    //list closed pull-requests
    result += '<br />**List of closed pull-requests since last release**<br />'
    for (def item : content) {
        if (item.pull_request && !isExcluded(item, config.excludeLabels)) {
            result += "[#${item.number}](${item.html_url}): ${item.title}<br />"
        }
    }
    //list closed issues
    result += '<br />**List of closed issues since last release**<br />'
    for (def item : content) {
        if (!item.pull_request && !isExcluded(item, config.excludeLabels)) {
            result += "[#${item.number}](${item.html_url}): ${item.title}<br />"
        }
    }
    return result
}

String addDeltaToLastRelease(config, latestTag){
    def result = ''
    //add delta link to previous release
    result += '<br />**Changes**<br />'
    result += "[${latestTag}...${config.version}](${config.githubServerUrl}/${config.githubOrg}/${config.githubRepo}/compare/${latestTag}...${config.version}) <br />"
    return result
}

void postNewRelease(config, TOKEN, releaseBody){
    releaseBody = releaseBody.replace('"', '\\"')
    //write release information
    def data = "{\"tag_name\": \"${config.version}\",\"target_commitish\": \"master\",\"name\": \"${config.version}\",\"body\": \"${releaseBody}\",\"draft\": false,\"prerelease\": false}"
    try {
        httpRequest httpMode: 'POST', requestBody: data, url: "${config.githubApiUrl}/repos/${config.githubOrg}/${config.githubRepo}/releases?access_token=${TOKEN}"
    } catch (e) {
        echo """[${STEP_NAME}] Error occured when writing release information
---------------------------------------------------------------------
Request body was:
---------------------------------------------------------------------
${data}
---------------------------------------------------------------------"""
        throw e
    }
}

boolean isExcluded(item, excludeLabels){
    def result = false
    if (!excludeLabels.isEmpty()) {
        excludeLabels.each {labelName ->
            item.labels.each { label ->
                if (label.name == labelName) {
                    result = true
                }
            }
        }
    }
    return result
}