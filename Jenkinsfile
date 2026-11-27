def mavenDockerImage = 'maven:3-eclipse-temurin-21'
def mavenDockerArgs = '-v $HOME/.m2:/var/maven/.m2:z -v $HOME/.config:/var/maven/.config -v $HOME/.sonar:/var/maven/.sonar -u 1000 -e _JAVA_OPTIONS=-Duser.home=/var/maven -e MAVEN_CONFIG=/var/maven/.m2'

// master and tags fail on failing tests and checkstyle errors, other branches only become unstable
def isStrict() {
  return env.TAG_NAME != null || env.BRANCH_NAME == 'master'
}

def runStrictOrUnstable(String cmd) {
  if (isStrict()) {
    sh cmd
  } else {
    catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
      sh cmd
    }
  }
}

// A failed sonar analysis (e.g. a red quality gate) only marks the build unstable, until specified otherwise
def runSonar(String cmd) {
  if (env.SONAR_FAIL_ON_QUALITY_GATE == 'true') {
    sh cmd
  } else {
    catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
      sh cmd
    }
  }
}

pipeline {

  agent any

  options {
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '15', daysToKeepStr: '90', numToKeepStr: '')
    disableConcurrentBuilds()
    timeout(time: 20, unit: 'MINUTES')
  }

  environment {
    NEXUS_BASE = 'intranda-nexus::https://nexus.intranda.com/repository'
    // the tag without its "v", otherwise dev-SNAPSHOT
    BUILD_VERSION = "${env.TAG_NAME ? env.TAG_NAME.replaceFirst('^v', '') : 'dev-SNAPSHOT'}"
  }

  stages {

    // ─────────────────────────────────────────────────────────────────────────
    // 1. BUILD
    // ─────────────────────────────────────────────────────────────────────────
    stage('build') {
      agent {
        docker {
          image mavenDockerImage
          args mavenDockerArgs
          reuseNode true
        }
      }
      steps {
        sh 'git reset --hard HEAD && git clean -fdx'
        sh 'mvn clean install -U -Drevision=$BUILD_VERSION -DskipTests -Dcheckstyle.skip=true -Djacoco.skip=true --no-transfer-progress'
        archiveArtifacts artifacts: '**/target/*.jar, install/*', fingerprint: true, allowEmptyArchive: true, onlyIfSuccessful: true
      }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. TEST + LINT  (parallel unit tests and checkstyle)
    // ─────────────────────────────────────────────────────────────────────────
    stage('test + lint') {
      parallel {

        stage('test') {
          agent {
            docker {
              image mavenDockerImage
              args mavenDockerArgs
              reuseNode true
            }
          }
          steps {
            runStrictOrUnstable('mvn test -Dmaven.main.skip=true -Drevision=$BUILD_VERSION --no-transfer-progress')
            junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
            step([
                    $class           : 'JacocoPublisher',
                    execPattern      : '**/target/jacoco.exec',
                    classPattern     : '**/target/classes/',
                    sourcePattern    : '**/src/main/java',
                    exclusionPattern : '**/*Test.class'
            ])
          }
        }

        stage('checkstyle') {
          agent {
            docker {
              image mavenDockerImage
              args mavenDockerArgs
              reuseNode true
            }
          }
          steps {
            script {
              runStrictOrUnstable('mvn checkstyle:check -Drevision=$BUILD_VERSION --no-transfer-progress')
              // Set plugin specific checkstyle thresholds like PLUGIN_NAME_THRESHOLD = VALUE in Jenkins settings
              def pluginName = env.JOB_NAME.tokenize('/')[1].replace('-', '_').toUpperCase()
              def thresholdKey = "${pluginName}_THRESHOLD"
              def threshold = env[thresholdKey] ?: env.PLUGIN_CHECKSTYLE_THRESHOLD ?: '100'
              recordIssues(
                      id: 'checkstyle-plugin',
                      tools: [checkStyle(pattern: '**/target/checkstyle-result.xml')],
                      qualityGates: [
                              [threshold: 1, type: 'TOTAL_HIGH', unstable: false],
                              [threshold: threshold as int, type: 'TOTAL_NORMAL', unstable: true]
                      ]
              )
            }
          }
        }

      }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. SONAR  (fails the build only with SONAR_FAIL_ON_QUALITY_GATE=true, otherwise unstable)
    // ─────────────────────────────────────────────────────────────────────────
    stage('sonar') {
      when {
        allOf {
          anyOf {
            branch 'master'
            buildingTag()
          }
          not { expression { return fileExists('DO_NOT_PUBLISH') } }
        }
      }
      agent {
        docker {
          image mavenDockerImage
          args mavenDockerArgs
          reuseNode true
        }
      }
      options {
        timeout(time: 10, unit: 'MINUTES')
      }
      steps {
        withCredentials([string(credentialsId: 'jenkins-sonarcloud', variable: 'SONAR_TOKEN')]) {
          runSonar('mvn sonar:sonar -Drevision=$BUILD_VERSION -Dsonar.token=$SONAR_TOKEN -Dsonar.qualitygate.wait=true --no-transfer-progress')
        }
      }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. DEPLOY  (master only: root pom + lib module if present)
    //    Private plugins (DO_NOT_PUBLISH) deploy to internal Nexus
    // ─────────────────────────────────────────────────────────────────────────
    stage('deploy') {
      when {
        branch 'master'
      }
      agent {
        docker {
          image mavenDockerImage
          args mavenDockerArgs
          reuseNode true
        }
      }
      steps {
        script {
          if (fileExists('module-lib/pom.xml')) {
            def repo = fileExists('DO_NOT_PUBLISH') ? "\$NEXUS_PRIVATE_REPO" : "\$NEXUS_PUBLIC_REPO"
            def altRepo = "-DaltDeploymentRepository=${env.NEXUS_BASE}/${repo}-releases" +
                          " -DaltSnapshotDeploymentRepository=${env.NEXUS_BASE}/${repo}-snapshots"
            sh "mvn -N deploy -Dmaven.main.skip=true -Dmaven.test.skip=true -Drevision=\$BUILD_VERSION -U ${altRepo} --no-transfer-progress"
            sh "mvn -f module-lib/pom.xml deploy -Dmaven.main.skip=true -Dmaven.test.skip=true -Drevision=\$BUILD_VERSION -U ${altRepo} --no-transfer-progress"
          }
        }
      }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. UPDATE PARENT  (master only: advance the submodule pointer in goobi-workflow)
    // ─────────────────────────────────────────────────────────────────────────
    stage('update-parent') {
      when {
        branch 'master'
      }
      agent {
        docker {
          image mavenDockerImage
          args mavenDockerArgs
          reuseNode true
        }
      }
      steps {
        withCredentials([gitUsernamePassword(credentialsId: '93f7e7d3-8f74-4744-a785-518fc4d55314', gitToolName: 'git-tool')]) {
          sh '''#!/bin/bash -xe
            PLUGIN_NAME=$(basename "$(git remote get-url origin)" .git)
            BRANCH="master"
            SUBMODULE_PATH="workflow-plugins/$PLUGIN_NAME"

            WORK_DIR=$(mktemp -d)
            git clone --depth 1 --branch "$BRANCH" "$CORE_REPO_URL" "$WORK_DIR"
            cd "$WORK_DIR"
            git submodule update --init --remote -- "$SUBMODULE_PATH"
            if git status --porcelain --ignore-submodules=none -- "$SUBMODULE_PATH" | grep -q .; then
              git add "$SUBMODULE_PATH"
              git commit -m "Update ${PLUGIN_NAME} to latest master"
              git push origin "$BRANCH"
            else
              echo "Submodule already up to date."
            fi
            rm -rf "$WORK_DIR"
          '''
        }
      }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. GITEA RELEASE  (v* tags only: publish the install archive, jars and config files)
    // ─────────────────────────────────────────────────────────────────────────
    stage('gitea-release') {
      when {
        buildingTag()
      }
      steps {
        withCredentials([usernamePassword(credentialsId: '93f7e7d3-8f74-4744-a785-518fc4d55314',
                                          usernameVariable: 'GITEA_USER',
                                          passwordVariable: 'GITEA_TOKEN')]) {
          sh '''#!/bin/bash -e
            PLUGIN_NAME=$(basename "$(git remote get-url origin)" .git)
            bash .ci/package-release.sh "$PLUGIN_NAME"

            # ── gitea coordinates from the origin remote ──
            URL=$(git remote get-url origin)
            case "$URL" in
              *://*) HOSTPATH=${URL#*://}; HOSTPATH=${HOSTPATH#*@}
                     HOST=${HOSTPATH%%/*}; REPO_PATH=${HOSTPATH#*/} ;;
              *)     HOSTPATH=${URL#*@}
                     HOST=${HOSTPATH%%:*}; REPO_PATH=${HOSTPATH#*:} ;;
            esac
            REPO_PATH=${REPO_PATH%.git}
            API="https://$HOST/api/v1/repos/$REPO_PATH"

            # ── release notes: commits since the previous tag (best effort) ──
            PREV_TAG=$(git describe --tags --abbrev=0 "${TAG_NAME}^" 2>/dev/null || true)
            if [ -n "$PREV_TAG" ]; then
              NOTES=$(git log --no-merges --pretty=format:'- %s' "$PREV_TAG..$TAG_NAME")
            else
              NOTES=$(git log --no-merges --pretty=format:'- %s' -n 20 "$TAG_NAME")
            fi
            NOTES=$(printf '%s' "$NOTES" | sed -e 's/\\\\/\\\\\\\\/g' -e 's/"/\\\\"/g' | sed ':a;N;$!ba;s/\\n/\\\\n/g')

            # ── create the release (reuse it if the tag already has one) ──
            REQ=$(printf '{"tag_name":"%s","name":"Release %s","body":"%s","draft":false,"prerelease":false}' \
                  "$TAG_NAME" "$TAG_NAME" "$NOTES")
            RESPONSE=$(curl -sS -u "$GITEA_USER:$GITEA_TOKEN" -H 'Content-Type: application/json' \
                            -X POST -d "$REQ" "$API/releases")
            RELEASE_ID=$(printf '%s' "$RESPONSE" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
            if [ -z "$RELEASE_ID" ]; then
              echo "Could not create release ($RESPONSE), looking for an existing one."
              RESPONSE=$(curl -sS -u "$GITEA_USER:$GITEA_TOKEN" "$API/releases/tags/$TAG_NAME")
              RELEASE_ID=$(printf '%s' "$RESPONSE" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
            fi
            if [ -z "$RELEASE_ID" ]; then
              echo "No gitea release for $TAG_NAME: $RESPONSE"
              exit 1
            fi

            for asset in target/release-assets/*; do
              NAME=$(basename "$asset")
              echo "Uploading $NAME"
              curl -sS -f -u "$GITEA_USER:$GITEA_TOKEN" -X POST \
                   -F "attachment=@${asset}" \
                   "$API/releases/$RELEASE_ID/assets?name=$(printf '%s' "$NAME" | sed 's/ /%20/g')" > /dev/null
            done
          '''
        }
        archiveArtifacts artifacts: 'target/release-assets/*.tar.gz', fingerprint: true, allowEmptyArchive: true
      }
    }

  }

  post {
    changed {
      emailext(
              subject: '${DEFAULT_SUBJECT}',
              body: '${DEFAULT_CONTENT}',
              recipientProviders: [requestor(), culprits()],
              attachLog: true
      )
    }
  }

}
/* vim: set ts=2 sw=2 tw=120 et :*/
