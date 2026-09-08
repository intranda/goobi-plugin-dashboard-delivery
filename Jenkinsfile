def mavenDockerImage = 'maven:3-eclipse-temurin-21'
def mavenDockerArgs = '-v $HOME/.m2:/var/maven/.m2:z -v $HOME/.config:/var/maven/.config -v $HOME/.sonar:/var/maven/.sonar -u 1000 -e _JAVA_OPTIONS=-Duser.home=/var/maven -e MAVEN_CONFIG=/var/maven/.m2'

pipeline {

  agent any

  options {
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '15', daysToKeepStr: '90', numToKeepStr: '')
    disableConcurrentBuilds()
    timeout(time: 10, unit: 'MINUTES')
  }

  environment {
    NEXUS_BASE = 'intranda-nexus::https://nexus.intranda.com/repository'
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
        script {
          env.BUILD_VERSION = env.TAG_NAME ? env.TAG_NAME.replaceAll('^v', '') : 'dev-SNAPSHOT'
        }
        sh "mvn clean install -U -Drevision=\$BUILD_VERSION -DskipTests -Dcheckstyle.skip=true -Djacoco.skip=true -P '!local-development' --no-transfer-progress"
        archiveArtifacts artifacts: '**/target/*.jar, install/*', fingerprint: true, allowEmptyArchive: true, onlyIfSuccessful: true
      }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. TEST + LINT  (not v*: parallel unit tests and checkstyle)
    // ─────────────────────────────────────────────────────────────────────────
    stage('test + lint') {
      when {
        not { branch 'v*' }
      }
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
            script {
              def strict = env.BRANCH_NAME == 'master'
              def cmd = "mvn test -Dmaven.main.skip=true -Drevision=\$BUILD_VERSION -P '!local-development' --no-transfer-progress"
              if (strict) {
                sh cmd
              } else {
                catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                  sh cmd
                }
              }
            }
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
              def strict = (env.BRANCH_NAME == 'master') && !env.NO_STRICT_CHECKSTYLE
              def cmd = "mvn checkstyle:check -Drevision=\$BUILD_VERSION -P '!local-development' --no-transfer-progress"
              if (strict) {
                sh cmd
              } else {
                catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                  sh cmd
                }
              }
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
    // 3. DEPLOY  (master only: root pom + lib module if present)
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
    // 4. UPDATE PARENT  (master only: advance submodule pointer in core or collection)
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
            PLUGIN_NAME=$(basename $(git remote get-url origin) .git)

            BRANCH="master"
            if [ -f "DO_NOT_PUBLISH" ]; then
              REPO_URL="$COLLECTION_REPO_URL"
              SUBMODULE_PATH="private-plugins/$PLUGIN_NAME"
            else
              REPO_URL="$CORE_REPO_URL"
              SUBMODULE_PATH="plugins/$PLUGIN_NAME"
            fi

            WORK_DIR=$(mktemp -d)
            git clone --depth 1 --branch $BRANCH "$REPO_URL" "$WORK_DIR"
            cd "$WORK_DIR"
            git submodule update --init --remote -- "$SUBMODULE_PATH"
            if git status --porcelain --ignore-submodules=none -- "$SUBMODULE_PATH" | grep -q .; then
              git add "$SUBMODULE_PATH"
              git commit -m "Update ${PLUGIN_NAME} to latest master"
              git push origin $BRANCH
            else
              echo "Submodule already up to date."
            fi
            rm -rf "$WORK_DIR"
          '''
        }
      }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. GITEA RELEASE  (v* tags only: publish jars, configs and install archive)
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
            # goobi-plugin-<type>-<name> → <type> (step, workflow, import, export, ...)
            PLUGIN_TYPE=$(echo "$PLUGIN_NAME" | sed -E 's/^goobi-plugin-([^-]+).*/\\1/')

            # ── stage the installable tree: goobi/{lib,plugins/<type>,plugins/GUI} ──
            STAGE=$(mktemp -d)
            ROOT="$STAGE/goobi"
            mkdir -p "$ROOT/lib" "$ROOT/plugins/$PLUGIN_TYPE" "$ROOT/plugins/GUI"

            # Only top level target/ dirs of the root project and its modules; no
            # sources/javadoc/test jars and no shade leftovers.
            mapfile -t JARS < <(find . -mindepth 2 -maxdepth 3 -type f -name '*.jar' \
                                  ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
                                  ! -name '*-tests.jar'   ! -name 'original-*.jar' \
                                | grep -E '^\\./([^/]+/)?target/[^/]+\\.jar$' || true)

            if [ ${#JARS[@]} -eq 0 ]; then
              echo "No jars found - nothing to release."
              exit 1
            fi

            for jar in "${JARS[@]}"; do
              module=$(basename "$(dirname "$(dirname "$jar")")")
              case "$module" in
                # base module (or a single module project without module-* dirs)
                module-base|.) dest="$ROOT/plugins/$PLUGIN_TYPE" ;;
                module-gui)    dest="$ROOT/plugins/GUI" ;;
                # module-lib, module-api, module-job, ... are plain libraries
                *)             dest="$ROOT/lib" ;;
              esac
              echo "$jar -> ${dest#$ROOT/}"
              cp "$jar" "$dest/"
            done

            # drop the dirs we prepared but did not fill (e.g. GUI)
            find "$ROOT" -type d -empty -delete

            ARCHIVE="$STAGE/${PLUGIN_NAME}.tar.gz"
            tar -czf "$ARCHIVE" -C "$STAGE" goobi
            cp "$ARCHIVE" .

            # ── assets: the archive, the plain jars and the config files ──
            ASSETS=("$ARCHIVE" "${JARS[@]}")
            if [ -d install ]; then
              for f in install/*; do
                if [ -f "$f" ]; then
                  ASSETS+=("$f")
                fi
              done
            fi

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

            for asset in "${ASSETS[@]}"; do
              NAME=$(basename "$asset")
              echo "Uploading $NAME"
              curl -sS -f -u "$GITEA_USER:$GITEA_TOKEN" -X POST \
                   -F "attachment=@${asset}" \
                   "$API/releases/$RELEASE_ID/assets?name=$(printf '%s' "$NAME" | sed 's/ /%20/g')" > /dev/null
            done

            rm -rf "$STAGE"
          '''
        }
        archiveArtifacts artifacts: '*.tar.gz', fingerprint: true, allowEmptyArchive: true
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