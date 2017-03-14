node {
  checkout scm

  commitHash = sh (
    script: 'git rev-parse HEAD',
    returnStdout: true
  ).trim()
  
  flowToken = '3b68626ba6383cd066505e7c462a4864'
  flowdockPostUrl = "https://api.flowdock.com/messages?flow_token=${flowToken}"
  gitUrl = 'git@bitbucket.org:insilica/systematic_review.git'

  if (
    env.CHANGE_TARGET == 'staging' ||
    env.BRANCH_NAME == 'staging') {
    branch = 'staging'
  } else if (
    env.CHANGE_TARGET == 'production' ||
    env.BRANCH_NAME == 'production') {
    branch = 'production'
  } else if (env.BRANCH_NAME != null) {
    branch = env.BRANCH_NAME
  } else if (env.CHANGE_TARGET != null) {
    branch = env.CHANGE_TARGET
  } else {
    branch = null
  }
  
  def sendFlowdockMsgFull = {
    msg,status,color ->
      try {
        threadId = "sysrev_${branch}_${env.BUILD_NUMBER}"
        if (status == null) {
          if (currentBuild.result == 'SUCCESS') {
            status = 'success'
            color = 'green'
          } else if (currentBuild.result == 'UNSTABLE') {
            status = 'unstable'
            color = 'yellow'
          } else if (currentBuild.result == 'FAILURE') {
            status = 'failure'
            color = 'red'
          } else if (currentBuild.result == null) {
            status = 'running'
            color = 'blue'
          } else {
            status = 'unknown'
            color = 'grey'
          }
        }
      
        body = """
{
\"flow_token\": \"${flowToken}\",
\"external_thread_id\": \"${threadId}\",
\"event\": \"activity\",
\"author\": {\"name\": \"jenkins\"},
\"title\": \"${msg}\",
\"thread\": {
\"title\": \"sysrev.${branch} pipeline\",
\"fields\": [],
\"body\": \"\",
\"external_url\": \"https://ws1.insilica.co/blue/organizations/jenkins/sysrev/detail/${branch}/${env.BUILD_NUMBER}/pipeline\",
\"status\": {\"color\": \"${color}\", \"value\": \"${status}\"}
}
}
"""
        echo "$body"
        response = httpRequest consoleLogResponseBody: true, contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: body, url: flowdockPostUrl
      } catch (exc) {
        echo "sendFlowdockMsgFull failed"
        throw exc
      }
  }

  def sendFlowdockMsg = {
    msg ->
      sendFlowdockMsgFull (msg, null, null)
  }
  
  stage('Init') {
    echo "branch = ${branch}"
    echo "CHANGE_TARGET = ${env.CHANGE_TARGET}"
    echo "BRANCH_NAME = ${env.BRANCH_NAME}"
    sendFlowdockMsg ('Build started')
    echo 'Setting up workspace...'
    try {
      sh './jenkins/init'
    } catch (exc) {
      currentBuild.result = 'FAILURE'
      sendFlowdockMsg ('Init stage failed')
      throw exc
    }
  }
  stage('Build') {
    echo 'Building...'
    try {
      sh './jenkins/build'
    } catch (exc) {
      currentBuild.result = 'FAILURE'
      sendFlowdockMsg ('Build stage failed')
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
        } else {
          sendFlowdockMsg ('Tests passed')
        }
      } catch (exc) {
        currentBuild.result = 'UNSTABLE'
        sendFlowdockMsg ('Tests failed')
      } finally {
        junit 'target/junit-all.xml'
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
            sh './jenkins/clone-db-to-dev'
            try {
              sh './jenkins/test-aws-dev-all'
              currentBuild.result = 'SUCCESS'
            } catch (exc) {
              currentBuild.result = 'UNSTABLE'
            } finally {
              junit 'target/junit-browser.xml'
            }
          }
        } catch (exc) {
          currentBuild.result = 'UNSTABLE'
        } finally {
          if (currentBuild.result == 'SUCCESS') {
            sendFlowdockMsgFull ('PreDeployTest passed','running','blue')
          }
          if (currentBuild.result == 'UNSTABLE') {
            sendFlowdockMsg ('PreDeployTest failed')
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
          } else {
            sendFlowdockMsg ('Deploy failed')
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
        } finally {
          junit 'target/junit-browser.xml'
        }
      }
    }
  }
}
