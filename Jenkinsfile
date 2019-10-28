node {
  checkout scm

  commitHash = sh (
    script: 'git rev-parse HEAD',
    returnStdout: true
  ).trim()

  gitUrl = 'git@bitbucket.org:insilica/systematic_review.git'

  if (env.BRANCH_NAME != null) {
    branch = env.BRANCH_NAME
  } else if (env.CHANGE_TARGET != null) {
    branch = env.CHANGE_TARGET
  } else {
    branch = null
  }

  jenkinsHost = "builds.insilica.co"
  jenkinsJob = "sysrev"
  jenkinsBuildUrl = "https://${jenkinsHost}/blue/organizations/jenkins/${jenkinsJob}/detail/${branch}/${env.BUILD_NUMBER}/pipeline"
  jenkinsConsoleUrl = "https://${jenkinsHost}/job/${jenkinsJob}/job/${branch}/${env.BUILD_NUMBER}/console"
  jenkinsSlackLinks = "(<${jenkinsBuildUrl}|Open>) (<${jenkinsConsoleUrl}|Console>)"

  def sendSlackMsgFull = {
    msg,color ->
    if (color == 'blue') {
      colorVal = '#4071bf'
    } else if (color != null) {
      colorVal = color
    } else {
      if (currentBuild.result == 'FAILURE') {
        colorVal = 'danger'
      } else if (currentBuild.result == 'UNSTABLE') {
        colorVal = 'warning'
      } else if (currentBuild.result == 'SUCCESS') {
        colorVal = 'good'
      }
    }
    msgContent = "[${env.JOB_NAME} #${env.BUILD_NUMBER}] ${msg} | ${jenkinsSlackLinks}"
    if (colorVal != null) {
      slackSend color: colorVal, message: msgContent
    } else {
      slackSend message: msgContent
    }
  }

  def sendSlackMsg = {
    msg ->
    sendSlackMsgFull (msg, null)
  }

  stage('Init') {
    echo "branch = ${branch}"
    echo "CHANGE_TARGET = ${env.CHANGE_TARGET}"
    echo "BRANCH_NAME = ${env.BRANCH_NAME}"
    sendSlackMsgFull ('Build started','blue')
    echo 'Setting up workspace...'
    try {
      sh './jenkins/init'
      sh './jenkins/init-build'
    } catch (exc) {
      currentBuild.result = 'FAILURE'
      sendSlackMsg ('Init stage failed')
      throw exc
    }
  }
  stage('Test') {
    if (currentBuild.result != 'FAILURE' &&
        branch != 'staging' &&
        branch != 'production' ) {
      echo 'Running build tests...'
      try {
        sh './jenkins/test'
        currentBuild.result = 'SUCCESS'
        if (branch == 'staging' ||
            branch == 'production') {
          sendSlackMsgFull ('Tests passed','blue')
        } else {
          sendSlackMsg ('Tests passed')
        }
      } catch (exc) {
        currentBuild.result = 'UNSTABLE'
        sendSlackMsg ('Tests failed')
        sh 'cat target/junit-all.xml'
        try {
          sh 'lein test'
        } catch (leinTestExc) {
        }
      } finally {
        junit 'target/junit-all.xml'
      }
    }
  }
  stage('Build') {
    if (branch == 'staging' ||
        branch == 'production') {
      if (currentBuild.result == 'SUCCESS') {
        echo 'Building...'
        try {
          sh './jenkins/build'
        } catch (exc) {
          currentBuild.result = 'FAILURE'
          sendSlackMsg ('Build stage failed')
          throw exc
        }
      }
    }
  }
  stage('PreDeployTest') {
    if (branch == 'production') {
      if (currentBuild.result == 'SUCCESS') {
        echo 'Running full tests against dev instance...'
        try {
          sshagent(['sysrev-admin']) {
            withEnv(["SYSREV_HOST=staging.sysrev.com"]) {
              sh './jenkins/migrate.dev'
              sh './jenkins/deploy'
            }
            try {
              sh './jenkins/test-aws-dev-all'
              currentBuild.result = 'SUCCESS'
            } catch (exc) {
              currentBuild.result = 'UNSTABLE'
              sh 'cat target/junit-all.xml'
            } finally {
              junit 'target/junit-all.xml'
            }
          }
        } catch (exc) {
          currentBuild.result = 'UNSTABLE'
        } finally {
          if (currentBuild.result == 'SUCCESS') {
            sendSlackMsgFull ('PreDeployTest passed','blue')
          }
          if (currentBuild.result == 'UNSTABLE') {
            sendSlackMsg ('PreDeployTest failed')
          }
        }
      }
    }
  }
  stage('Deploy') {
    if (branch == 'staging' ||
        branch == 'production') {
      if (currentBuild.result == 'SUCCESS') {
        echo 'Deploying build to host...'
        try {
          if (branch == 'staging') {
            sshagent(['sysrev-admin']) {
              withEnv(["SYSREV_HOST=staging.sysrev.com"]) {
                sh './jenkins/migrate.dev'
                sh './jenkins/deploy'
              }
            }
          }
          if (branch == 'production') {
            archiveArtifacts artifacts: 'sysrev-web-0.1.0-SNAPSHOT-standalone.jar,target/*.jar,deploy/client.tgz', fingerprint: true
            sshagent(['sysrev-admin']) {
              withEnv(["SYSREV_HOST=sysrev.com"]) {
                sh './jenkins/migrate.prod'
                sh './jenkins/deploy'
              }
            }
          }
        } catch (exc) {
          currentBuild.result = 'FAILURE'
          throw exc
        } finally {
          if (currentBuild.result == 'SUCCESS') {
            sendSlackMsgFull ('Deployed to AWS', 'blue')
          } else {
            sendSlackMsg ('Deploy failed')
          }
        }
      }
    }
  }
  stage('GitPublish') {
    if (branch == 'production') {
      if (currentBuild.result == 'SUCCESS') {
        try {
          sshagent(['sysrev-admin']) {
            sh "git push ${gitUrl} HEAD:master -f"
          }
        } catch (exc) {
          currentBuild.result = 'UNSTABLE'
          sendSlackMsg ('GitPublish failed')
        }
      }
    }
  }
  stage('PostDeployTest') {
    if (branch == 'staging' ||
        branch == 'production') {
      if (currentBuild.result == 'SUCCESS') {
        echo 'Running tests against deploy host...'
        try {
          if (branch == 'staging') {
            sh './jenkins/test-aws-dev-all'
          }
          if (branch == 'production') {
            sh './jenkins/test-aws-prod-browser'
          }
          currentBuild.result = 'SUCCESS'
          sendSlackMsg ('PostDeployTest passed')
        } catch (exc) {
          currentBuild.result = 'UNSTABLE'
          sendSlackMsg ('PostDeployTest failed')
          if (branch == 'staging') {
            sh 'cat target/junit-all.xml'
          }
          if (branch == 'production') {
            sh 'cat target/junit-browser.xml'
          }
        } finally {
          if (branch == 'staging') {
            junit 'target/junit-all.xml'
          }
          if (branch == 'production') {
            junit 'target/junit-browser.xml'
          }
        }
      }
    }
  }
}
