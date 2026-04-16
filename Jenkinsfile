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
        DOCKER_HOST = "unix:///Users/jenkins/.colima/amd64/docker.sock"
    }

    stages{

        stage('Build JAR file') {
            steps{
                sh "mvn clean install -DskipTests"
            }
        }

        // stage('OWASP Dependency Scan') {
        //     environment {
        //         OWASP_INSTALLATION_ID = "owasp-12.1.1"
        //         NVD_API_KEY = 'NVD_API_KEY'
        //     }
        //     steps {
        //         // sh "mkdir -p /Users/jenkins/dependency-check-data"
        //         script{
        //             owaspDependencyCheck(dirPath: '.', nvdApiKey: NVD_API_KEY, scanPath: 'target/**/*.jar', owaspInstallation: OWASP_INSTALLATION_ID,
        //                 dataDir: '/Users/jenkins/dependency-check-data',
        //                 failedTotalCritical: 1,
        //                 failedTotalHigh: 4,
        //                 failedTotalMedium: 8,
        //                 failedTotalLow: 90,
        //                 outputDir: './',
        //                 outputFile: 'dependency-check-report.xml',
        //                 stopBuild: true
        //             )
        //         }
        //     }
        // }

        stage('Unit Tests') {
            steps{
                sh "mvn test"
                sh "mvn surefire-report:report-only site:site -DgenerateReports=false"
            }
            post {
                always {
                    junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: false
                    publishHTML(target: [
                        allowMissing         : false,
                        alwaysLinkToLastBuild: true,
                        keepAll              : true,
                        reportDir            : 'target/site',
                        reportFiles          : 'surefire-report.html',
                        reportName           : 'Unit Test Report'
                    ])
                }
            }
        }

        stage('Coverage') {
            steps{
                sh "mvn jacoco:report"
            }
            post {
                always {
                    publishHTML(target: [
                        allowMissing         : false,
                        alwaysLinkToLastBuild: true,
                        keepAll              : true,
                        reportDir            : 'target/site/jacoco',
                        reportFiles          : 'index.html',
                        reportName           : 'JaCoCo Coverage Report'
                    ])
                }
            }
        }

        stage('Generate Docker Image'){
            steps{
                script {
                    def timestamp = sh(script: "date '+%Y-%m-%d_%H-%M-%S'", returnStdout: true).trim()
                    env.DOCKER_IMAGE_TAG = "${DOCKER_IMAGE_VERSION}_${timestamp}"
                }
                // sh """
                //     docker rmi eclipse-temurin:21-jre-alpine || true
                //     docker pull --platform linux/amd64 eclipse-temurin:21-jre-alpine
                // """
                withCredentials([usernamePassword(credentialsId: NEXUS_CREDS_ID, usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                    sh """
                        mkdir -p ${DOCKER_CONFIG}
                        AUTH=\$(printf '%s:%s' "\$DOCKER_USER" "\$DOCKER_PASS" | base64 | tr -d '\\n')
                        printf '{"auths":{"%s":{"auth":"%s"}}}' "${NEXUS_URL}" "\$AUTH" > ${DOCKER_CONFIG}/config.json
                        docker build -t ${NEXUS_URL}/repository/${NEXUS_REPOSITORY}/${DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG} .
                    """
                }
            }
        }

        stage('Image Security Scan') {
            steps {
                sh """
                    syft ${NEXUS_URL}/repository/${NEXUS_REPOSITORY}/${DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG} \
                        -o cyclonedx-json > sbom.json
                    grype sbom:./sbom.json \
                        --output "template=grype-report.html" \
                        --template ci/grype-report.html.tmpl
                    grype sbom:./sbom.json \
                        --output table \
                        --fail-on critical
                """
            }
            post {
                always {
                    publishHTML(target: [
                        allowMissing         : false,
                        alwaysLinkToLastBuild: true,
                        keepAll              : true,
                        reportDir            : '.',
                        reportFiles          : 'grype-report.html',
                        reportName           : 'Security Scan Report'
                    ])
                }
            }
        }

        stage('Push Docker Image'){
            steps{
                sh "docker push ${NEXUS_URL}/repository/${NEXUS_REPOSITORY}/${DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG}"
            }
        }

        stage('Deploy') {
            when {
                branch 'develop'
            }
            environment {
                DEPLOY_SERVER = '65.108.127.117'
                DEPLOY_USER   = 'houssem'
            }
            steps {
                withCredentials([
                    usernamePassword(credentialsId: NEXUS_CREDS_ID, usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASS'),
                    string(credentialsId: 'survey-notification-internal-token', variable: 'INTERNAL_TOKEN')
                ]) {
                    sshagent(credentials: ['devops-server-ssh']) {
                        sh """
                            ssh -o StrictHostKeyChecking=no ${DEPLOY_USER}@${DEPLOY_SERVER} bash -s << 'ENDSSH'
                                RUNNING=\$(docker ps -aq --filter name=${DOCKER_IMAGE_NAME})
                                if [ -n "\$RUNNING" ]; then
                                    echo "Stopping and removing existing container: ${DOCKER_IMAGE_NAME}"
                                    docker stop ${DOCKER_IMAGE_NAME}
                                    docker rm ${DOCKER_IMAGE_NAME}
                                fi
                                echo "${NEXUS_PASS}" | docker login ${NEXUS_URL} -u ${NEXUS_USER} --password-stdin
                                docker pull ${NEXUS_URL}/repository/${NEXUS_REPOSITORY}/${DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG}
                                docker run -d \\
                                    --name ${DOCKER_IMAGE_NAME} \\
                                    -p 8085:8085 \\
                                    --network notification \\
                                    -e SPRING_DATA_MONGODB_URI='mongodb://notification-mongodb:27017/notification_db?connectTimeoutMS=2000&socketTimeoutMS=5000&serverSelectionTimeoutMS=3000' \\
                                    -e SPRING_DATA_REDIS_HOST=notification-redis \\
                                    -e SPRING_DATA_REDIS_PORT=6379 \\
                                    -e INTERNAL_TOKEN=${INTERNAL_TOKEN} \\
                                    --restart unless-stopped \\
                                    ${NEXUS_URL}/repository/${NEXUS_REPOSITORY}/${DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG}
                                echo "Deployment complete. Container ${DOCKER_IMAGE_NAME} is running."
ENDSSH
                        """
                    }
                }
            }
        }

        stage('DAST — ZAP Baseline Scan') {
            when {
                branch 'develop'
            }
            environment {
                DEPLOY_SERVER = '65.108.127.117'
                ZAP_TARGET    = 'http://65.108.127.117:8085'
            }
            steps {
                sleep 10
                retry(3) {
                    sleep 2
                    sh """
                        docker run --rm \
                            -v \$(pwd):/zap/wrk/:rw \
                            ghcr.io/zaproxy/zaproxy:stable \
                            zap-baseline.py \
                                -t ${ZAP_TARGET} \
                                -r zap-report.html \
                                -I
                    """
                }
            }
            post {
                always {
                    publishHTML(target: [
                        allowMissing         : true,
                        alwaysLinkToLastBuild: true,
                        keepAll              : true,
                        reportDir            : '.',
                        reportFiles          : 'zap-report.html',
                        reportName           : 'ZAP DAST Report'
                    ])
                }
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
