@Library('hippo-jenkins-shared@master') _

pipeline {

    agent 'mac-mini'

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
        maven '3.6.3'
    }
    
    environment {
        NEXUS_REPOSITORY = "survey-notification-service"
        DOCKER_IMAGE_NAME = "notification-service"
        DOCKER_IMAGE_VERSION = "0.0.1-test-jenkins"
        NEXUS_URL = "nexus.atlas-labs.org"
        NEXUS_CREDS_ID = "nexus"
    }

    stages{
        
        stage('Build JAR file') {
            steps{
                sh "mvn clean install -DskipTests"
            }
        }

        stage('OWASP Dependency Scan') {
            steps {
                script{
                    owaspDependencyCheck(dirPath: serviceDirPath, nvdApiKey: owsapNvdApiKey, scanPath: 'target/**/*.jar', owaspInstallation: owaspInstallationId, 
                        failedTotalCritical: 1, 
                        failedTotalHigh: 4, 
                        failedTotalMedium: 8, 
                        failedTotalLow: 90, 
                        outputDir: './', 
                        outputFile: 'dependency-check-report.xml',
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
                script{
                    dockerLib.loginAndBuild(dirPath: ".", credentialsId: NEXUS_CREDS_ID,
                        nexusUrl: NEXUS_URL,
                        nexusRepository: NEXUS_REPOSITORY,
                        dockerImageName: DOCKER_IMAGE_NAME,
                        dockerImageVersion: DOCKER_IMAGE_VERSION
                    )
                }
            }
        }

        stage('Push Docker Image'){
            steps{
                script{
                    dockerLib.push(nexusUrl: NEXUS_URL, 
                        nexusRepository: NEXUS_REPOSITORY, 
                        dockerImageName: DOCKER_IMAGE_NAME, 
                        dockerImageVersion: DOCKER_IMAGE_VERSION
                    )
                }
            }
        }
    }
}