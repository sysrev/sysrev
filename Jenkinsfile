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
  
  def sendFlowdockMsgFull = {
    msg,status,color ->
    cmd = "./scripts/flowdock-msg pipeline"
    cmd += " -j sysrev"
    cmd += " -b ${branch}"
    cmd += " -n ${env.BUILD_NUMBER}"
    cmd += " -m '${msg}'"
    if (currentBuild.result != null) {
      cmd += " -r ${currentBuild.result}"
    }
    if (status != null) {
      cmd += " -s ${status}"
    }
    if (color != null) {
      cmd += " -c ${color}"
    }
    try {
      sh (cmd)
    } catch (exc) {
      echo "sendFlowdockMsgFull failed"
    }
  }

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
    if (colorVal != null) {
      slackSend color: colorVal, message: msg
    } else {
      slackSend message: msg
    }
  }

  def sendFlowdockMsg = {
    msg ->
    sendFlowdockMsgFull (msg, null, null)
  }

  def sendSlackMsg = {
    msg ->
    sendSlackMsgFull (msg, null)
  }
  
  stage('Init') {
    echo "branch = ${branch}"
    echo "CHANGE_TARGET = ${env.CHANGE_TARGET}"
    echo "BRANCH_NAME = ${env.BRANCH_NAME}"
    sendFlowdockMsg ('Build started')
    sendSlackMsgFull ('Build started','blue')
    echo 'Setting up workspace...'
    try {
      sh './jenkins/init'
    } catch (exc) {
      currentBuild.result = 'FAILURE'
      sendFlowdockMsg ('Init stage failed')
      sendSlackMsg ('Init stage failed')
      throw exc
    }
  }
  stage('Test') {
    if (currentBuild.result != 'FAILURE') {
      echo 'Running build tests...'
      try {
        sh './jenkins/test'
        currentBuild.result = 'SUCCESS'
        if (branch == 'staging' ||
            branch == 'production') {
          sendFlowdockMsgFull ('Tests passed','running','blue')
          sendSlackMsgFull ('Tests passed','blue')
        } else {
          sendFlowdockMsg ('Tests passed')
          sendSlackMsg ('Tests passed')
        }
      } catch (exc) {
        currentBuild.result = 'UNSTABLE'
        sendFlowdockMsg ('Tests failed')
        sendSlackMsg ('Tests failed')
        sh 'cat target/junit-all.xml'
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
          sendFlowdockMsg ('Build stage failed')
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
            withEnv(["SYSREV_HOST=isysrev-dev.ddns.net"]) {
              sh './jenkins/deploy'
            }
            // sh './jenkins/clone-db-to-dev'
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
            sendFlowdockMsgFull ('PreDeployTest passed','running','blue')
            sendSlackMsgFull ('PreDeployTest passed','blue')
          }
          if (currentBuild.result == 'UNSTABLE') {
            sendFlowdockMsg ('PreDeployTest failed')
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
              withEnv(["SYSREV_HOST=isysrev-dev.ddns.net"]) {
                sh './jenkins/deploy'
              }
            }
          }
          if (branch == 'production') {
            sh './jenkins/build'
            archiveArtifacts artifacts: 'target/*.jar,deploy/client.tgz', fingerprint: true
            sshagent(['sysrev-admin']) {
              withEnv(["SYSREV_HOST=sysrev.us"]) {
                sh './jenkins/deploy'
              }
            }
          }
        } catch (exc) {
          currentBuild.result = 'FAILURE'
          throw exc
        } finally {
          if (currentBuild.result == 'SUCCESS') {
            sendFlowdockMsg ('Deployed to AWS')
            sendSlackMsg ('Deployed to AWS')
          } else {
            sendFlowdockMsg ('Deploy failed')
            sendSlackMsg ('Deploy failed')
          }
        }
      }
    }
  }
  stage('GitPublish') {
    if (branch == 'staging') {
      if (currentBuild.result == 'SUCCESS') {
        try {
          sshagent(['sysrev-admin']) {
            sh "git push ${gitUrl} HEAD:master -f"
          }
        } catch (exc) {
          currentBuild.result = 'UNSTABLE'
          sendFlowdockMsg ('GitPublish failed')
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
            sh './jenkins/test-aws-dev-browser'
          }
          if (branch == 'production') {
            sh './jenkins/test-aws-prod-browser'
          }
          currentBuild.result = 'SUCCESS'
        } catch (exc) {
          currentBuild.result = 'UNSTABLE'
          sendFlowdockMsg ('PostDeployTest failed')
          sendSlackMsg ('PostDeployTest failed')
          sh 'cat target/junit-browser.xml'
        } finally {
          junit 'target/junit-browser.xml'
        }
      }
    }
  }
}
