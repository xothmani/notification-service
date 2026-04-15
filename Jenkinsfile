library(identifier: 'hippo-jenkins-shared@master',
        retriever: modernSCM([
            $class: 'GitSCMSource',
            remote: 'https://github.com/hippo-labs-inc/jenkins-shared.git',
            credentialsId: 'Jenkins-Github-App',
            traits: [[$class: 'jenkins.plugins.git.traits.BranchDiscoveryTrait']]
        ])
)

pipeline {

    agent { label 'mac-mini' }

    options{
        disableResume()
        disableConcurrentBuilds abortPrevious: true
        buildDiscarder(logRotator(
            numToKeepStr: '5',
            daysToKeepStr: '10',
            artifactNumToKeepStr: '5',
            artifactDaysToKeepStr: '10'
        ))
    }

    tools {
        maven '3.9.14'
        jdk '21.0.10+7'
    }

    environment {
        NEXUS_REPOSITORY = "survey-notification-service"
        DOCKER_IMAGE_NAME = "notification-service"
        DOCKER_IMAGE_VERSION = "0.0.1-test-jenkins"
        NEXUS_URL = "nexus.atlas-labs.org"
        NEXUS_CREDS_ID = "nexus"
        PATH = "/usr/local/bin:/opt/homebrew/bin:${env.PATH}"
        DOCKER_CONFIG = "/tmp/docker-config-${BUILD_NUMBER}"
        DOCKER_HOST = "unix:///Users/jenkins/.colima/default/docker.sock"
    }

    stages{

        stage('Build JAR file') {
            steps{
                sh "mvn clean install -DskipTests"
            }
        }

        stage('OWASP Dependency Scan') {
            environment {
                OWASP_INSTALLATION_ID = "owasp-12.1.0"
                NVD_API_KEY = 'NVD_API_KEY'
            }
            steps {
                script{
                    owaspDependencyCheck(dirPath: '.', nvdApiKey: NVD_API_KEY, scanPath: 'target/**/*.jar', owaspInstallation: OWASP_INSTALLATION_ID,
                        failedTotalCritical: 1,
                        failedTotalHigh: 4,
                        failedTotalMedium: 8,
                        failedTotalLow: 90,
                        outputDir: './',
                        outputFile: '**/dependency-check-report.xml',
                        stopBuild: true
                    )
                }
            }
        }

        stage('Unit Tests') {
            steps{
                sh "mvn test"
            }
        }

        stage('Generate Docker Image'){
            steps{
                withCredentials([usernamePassword(credentialsId: NEXUS_CREDS_ID, usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                    sh """
                        mkdir -p ${DOCKER_CONFIG}
                        AUTH=\$(printf '%s:%s' "\$DOCKER_USER" "\$DOCKER_PASS" | base64 | tr -d '\\n')
                        printf '{"auths":{"%s":{"auth":"%s"}}}' "${NEXUS_URL}" "\$AUTH" > ${DOCKER_CONFIG}/config.json
                        docker build -t ${NEXUS_URL}/repository/${NEXUS_REPOSITORY}/${DOCKER_IMAGE_NAME}:${DOCKER_IMAGE_VERSION} .
                    """
                }
            }
        }

        stage('Push Docker Image'){
            steps{
                sh "docker push ${NEXUS_URL}/repository/${NEXUS_REPOSITORY}/${DOCKER_IMAGE_NAME}:${DOCKER_IMAGE_VERSION}"
            }
        }
    }
    post {
        always {
            sh "rm -rf ${DOCKER_CONFIG} || true"
            cleanWs()
        }
    }
}
