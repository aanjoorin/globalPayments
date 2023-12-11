  environment {
    POM_PROJECT_VERSION = readMavenPom().getVersion()
    VERSION = readMavenPom().getVersion()
    PROJECT_REPO = getRepo().toLowerCase()
    GIT_COMMIT_HASH = getGitCommitHash()
    RELEASE_TAG = getGitTag('HEAD')
    SKIP_TESTS = skipTests(getValue(globalJsonSettings, 'skipTests'))
    NXIQ_STAGE = getNxiqStage(branch_name)
    NODE_EXTRA_CA_CERTS = "/etc/ssl/certs/CA.pem"
    NPMRC = credentials('npmrc')
    IS_RELEASE_BRANCH = isReleaseBranch(branch_name)
    IS_DEVELOP_BRANCH = isDevelopBranch(branch_name)
    POM_FILE_PATH = getValue(globalJsonSettings, 'pomFilePath')
  }
  stages {
    stage('Git Push') {
      when {
        anyOf {
          not { expression { getValue(globalJsonSettings, 'enableGithub').toBoolean()} }
        }
      }
      environment {
        REPO_LOCAL = getRepo()
        REPO_REMOTE = getValue(globalJsonSettings, 'remoteRepository')
      }
      steps {
        script {
          if ( env.CHANGE_BRANCH != null ) {
            println('Change/Pull Request Detected.\nUsing Branch: ' + env.CHANGE_BRANCH )
            env.BRANCH = env.CHANGE_BRANCH
          } else {
            println('Using Branch: ' + env.BRANCH_NAME)
            env.BRANCH = env.BRANCH_NAME
          }
        }
        withCredentials([usernamePassword(credentialsId: 'global.github-user',usernameVariable: 'USER',passwordVariable: 'PASS')]) {
          sh '''
            git config --global credential.${REPO_REMOTE}.helper "!f() { echo username=${USER}; echo password=${PASS}; }; f"
            git checkout origin/${BRANCH} --track
          '''
        }
        script {
          if ( getValue(globalJsonSettings, 'remoteRepository') == '' ) {
              println('Remote Repository not configured -- Skipping stage')
          } else {
            sh '''
              git remote add remote ${REPO_REMOTE}
              git push remote ${BRANCH}:${BRANCH}
            '''
            if ( getValue(globalJsonSettings, 'pushTags').toBoolean() ) {
              sh '''
                  git push remote --tags
              '''
            }
          }
          sh '''
              git checkout ${GIT_COMMIT_HASH}
              git config --global --remove-section credential.${REPO_REMOTE}
          '''
        }
      }
    }
    stage('Manage Version') {
      when {
        allOf {
          not { expression { return branch_name == 'master' } }
          not { expression { return branch_name == 'develop' } }
          not { expression { return branch_name =~ /^support/ } }
          not { expression { return branch_name =~ /^release/ } }
        }
      }
      steps {
        container('maven') {
          script {
            sh '''
              PREFIX=$(dirname ${GIT_BRANCH})
              BASENAME=$(basename ${GIT_BRANCH})
              BRANCH=$(echo ${BASENAME} | sed 's/[^-a-zA-Z_0-9]/-/g' | sed 's/--/-/g' | sed 's/-$//')
              echo $BRANCH
              VERSION=$(echo ${POM_PROJECT_VERSION} | sed 's/-SNAPSHOT//')-$BRANCH-SNAPSHOT
              echo $VERSION
              mvn -f ${POM_FILE_PATH} --batch-mode --global-settings /secrets/maven/globalsettings/globalsettings.xml --settings /settings/maven/settings/settings.xml -DgenerateBackupPoms=false versions:set versions:set-property -Dproperty=revision -DnewVersion=$VERSION
              '''
          }
        }
      }
    }
    stage('nxiq quick scan') {
      steps {
        container('maven') {
          script {
            sh '''
              export JDK_JAVA_OPTIONS="$JDK_JAVA_OPTIONS --add-opens=java.base/java.util=ALL-UNNAMED"
              applicationId=$(echo ${JOB_NAME} | awk -F "/" '{ print $1"."$2 }' )
              mvn -f ${POM_FILE_PATH} --global-settings /secrets/maven/globalsettings/globalsettings.xml \
                  --settings /settings/maven/settings/settings.xml \
                  package \
                  --batch-mode com.sonatype.clm:clm-maven-plugin:2.30.7-01:evaluate \
                  -DskipTests \
                  -Dclm.stage=build \
                  -Dclm.applicationId=${applicationId} \
                  -Dclm.serverUrl=https://nxiq.net.com \
                  -Dclm.serverId=nxiq.net.com
              '''
          }
        }
      }
    }
    stage('Maven Deploy') {
      steps {
        container('maven') {
          script {
            sh '''
              cp ${NPMRC} ~/.npmrc
              mvn -f ${POM_FILE_PATH} --batch-mode ${SKIP_TESTS} --global-settings /secrets/maven/globalsettings/globalsettings.xml --settings /settings/maven/settings/settings.xml org.jacoco:jacoco-maven-plugin:0.8.8:prepare-agent deploy org.jacoco:jacoco-maven-plugin:0.8.8:report
              '''
          }
        }
      }
    }
    stage('Build & Push Image') {
      steps {
        container(name: 'kaniko', shell: '/busybox/sh') {
          script {
            buildImages(globalJsonSettings)
          }
        }
      }
    }
    stage('api publish') {
      when {
        anyOf {
          expression { return branch_name == 'master' }
          expression { return branch_name == 'develop' }
          expression { return branch_name =~ /^support/ }
        }
      }
      steps {
        container('maven') {
          withCredentials([usernameColonPassword(credentialsId: 'nxrm-api-portal',variable: 'userPass')]) {
              sh '''
                  # use maven to generate a list of artifacts for the modules and filter them so we just the war and jar artifacts
                  MODULES="$(mvn -am -B --global-settings /secrets/maven/globalsettings/globalsettings.xml --settings /settings/maven/settings/settings.xml exec:exec -Dexec.executable=echo -Dexec.args='###MODULE_GAV### ${project.basedir}' | grep '###MODULE_GAV###' | cut -f2 -d' ')"

                  for MODULE in ${MODULES}
                  do
                    MODULE_NAME="$(mvn -am -B -N -f ${MODULE} --global-settings /secrets/maven/globalsettings/globalsettings.xml --settings /settings/maven/settings/settings.xml exec:exec -Dexec.executable=echo -Dexec.args='###MODULE_GAV### ${project.artifactId}' | grep '###MODULE_GAV###' | cut -f2 -d' ')"
                    echo "module: ${MODULE_NAME}"

                    ARTIFACT="$(mvn -am -B -N -f ${MODULE} --global-settings /secrets/maven/globalsettings/globalsettings.xml --settings /settings/maven/settings/settings.xml exec:exec -Dexec.executable=echo -Dexec.args='###MODULE_GAV### ${project.build.directory}/${project.build.finalName}.${project.packaging}' | grep '###MODULE_GAV###' | cut -f2 -d' ')"

                    echo "artifact: ${ARTIFACT}"

                    if echo "${ARTIFACT}" | grep -Pq "\\.(war|jar)$"; then

                      #### INFO.JSON ####

                      if test -f "info.json"; then
                        # make sure info.json doesn't already exist
                        rm info.json
                      fi

                      # some failures are expected, we can determine a successful run by the existence of the output file
                      java -jar ${ARTIFACT} --run-type=infocli --command=describe --output-file=info.json || echo

                      if test -f "info.json"; then
                        echo "info.json exists for ${MODULE_NAME}"
                        # if the output file gets created then upload it
                        curl --fail --silent --show-error --user "${userPass}" --upload-file info.json https://nxrm.net.com/repository/api-portal/${JOB_NAME}/${MODULE_NAME}/
                      fi

                      #### ADMINREADME.TXT ####

                      if test -f "adminreadme.txt"; then
                        # make sure adminreadme.txt doesn't already exist
                        rm adminreadme.txt
                      fi

                      # some failures are expected, we can determine a successful run by the existence of the output file
                      java -jar ${ARTIFACT} --run-type=adminreadmecli --command=describe --output-file=adminreadme.txt || echo

                      if ! test -f "adminreadme.txt"; then
                        echo "pre-0.14 adminreadme.txt"
                        java -jar ${ARTIFACT} --run-type=adminreadmecli --command=describe | awk '/\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*/{p=1}p\' > adminreadme.txt || echo
                      fi

                      if test -f "adminreadme.txt" && test -s "adminreadme.txt"; then
                        echo "adminreadme.txt exists for ${MODULE_NAME}"
                        # if the output file gets created then upload it
                        curl --fail --silent --show-error --user "${userPass}" --upload-file adminreadme.txt https://nxrm.net.com/repository/api-portal/${JOB_NAME}/${MODULE_NAME}/
                      fi

                      #### APPLICATION.PROPERTIES ####

                      if test -f "application.properties"; then
                        # make sure application.properties doesn't already exist
                        rm application.properties
                      fi

                      # some failures are expected, we can determine a successful run by the existence of the output file
                      java -jar ${ARTIFACT} --run-type=configcli --command=describe --file-type=application --output-file=application.properties || echo

                      if ! test -f "application.properties"; then
                        echo "pre-0.14 application.properties"
                        java -jar ${ARTIFACT} --run-type=configcli --command=describe --file-type=application | awk '/\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*/{p=1}p\' > application.properties || echo
                      fi

                      if test -f "application.properties" && test -s "application.properties"; then
                        echo "application.properties exists for ${MODULE_NAME}"
                        # if the output file gets created then upload it
                        curl --fail --silent --show-error --user "${userPass}" --upload-file application.properties https://nxrm.net.com/repository/api-portal/${JOB_NAME}/${MODULE_NAME}/
                      fi

                      #### BOOTSTRAP.PROPERTIES ####

                      if test -f "bootstrap.properties"; then
                        # make sure bootstrap.properties doesn't already exist
                        rm bootstrap.properties
                      fi

                      # some failures are expected, we can determine a successful run by the existence of the output file
                      java -jar ${ARTIFACT} --run-type=configcli --command=describe --file-type=bootstrap --output-file=bootstrap.properties || echo

                      if ! test -f "bootstrap.properties"; then
                        echo "pre-0.14 bootstrap.properties"
                        java -jar ${ARTIFACT} --run-type=configcli --command=describe --file-type=bootstrap | awk '/\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*/{p=1}p' > bootstrap.properties || echo
                      fi

                      if test -f "bootstrap.properties" && test -s "bootstrap.properties"; then
                        echo "bootstrap.properties exists for ${MODULE_NAME}"
                        # if the output file gets created then upload it
                        curl --fail --silent --show-error --user "${userPass}" --upload-file bootstrap.properties https://nxrm.net.com/repository/api-portal/${JOB_NAME}/${MODULE_NAME}/
                      fi

                    fi
                  done
              '''
          }
        }
      }
    }
    stage('nxiq Scan') {
      steps {
        container('maven') {
          script {
            sh '''
              export JDK_JAVA_OPTIONS="$JDK_JAVA_OPTIONS --add-opens=java.base/java.util=ALL-UNNAMED"
              applicationId=$(echo ${JOB_NAME} | awk -F "/" '{ print $1"."$2 }' )
              mvn -f ${POM_FILE_PATH} --global-settings /secrets/maven/globalsettings/globalsettings.xml \
                  --settings /settings/maven/settings/settings.xml \
                  --batch-mode com.sonatype.clm:clm-maven-plugin:2.30.7-01:evaluate \
                  -Dclm.stage=${NXIQ_STAGE} \
                  -Dclm.additionalScopes=test,provided,system \
                  -Dclm.applicationId=${applicationId} \
                  -Dclm.serverUrl=https://nxiq.net.com \
                  -Dclm.serverId=nxiq.net.com
              '''
          }
        }
      }
    }
    stage('Fortify Scan') {
      when {
        allOf {
          expression { getValue(globalJsonSettings, 'enableFortifyScan').toBoolean()}
          anyOf {
            branch 'develop'
            expression { return branch_name =~ /^hotfix/ }
            expression { return branch_name =~ /^support/ }
            expression { getValue(globalJsonSettings, 'forceFortifyScan').toBoolean()}
          }
        }
      }
      steps {
        container('fortify') {
          lock('fortify') {
          sh '''
            export PATH=/opt/Fortify/bin:$PATH
            export DOT_JOB_NAME=$(echo ${JOB_NAME} | sed "s/%2F/_/g" | tr / . )
            #env
            mvn -f ${POM_FILE_PATH} --batch-mode --settings /settings/maven/settings/settings.xml com.fortify.sca.plugins.maven:sca-maven-plugin::clean
            mvn -f ${POM_FILE_PATH} --batch-mode --settings /settings/maven/settings/settings.xml com.fortify.sca.plugins.maven:sca-maven-plugin::translate
            mvn -f ${POM_FILE_PATH} --batch-mode --settings /settings/maven/settings/settings.xml com.fortify.sca.plugins.maven:sca-maven-plugin::scan
            mv -v ${POM_FILE_PATH%pom.xml}target/fortify/*.fpr ./target/fortify/results.fpr
            '''
          } // lock fortify
          lock(env.JOB_NAME+'/fortify') {
          withCredentials([usernameColonPassword(credentialsId: 'nxrm-fortify',variable: 'userPass')]) {
          sh '''
            export PATH=/opt/Fortify/bin:$PATH
            export DOT_JOB_NAME=$(echo ${JOB_NAME} | sed "s/%2F/_/g" | tr / . )
            FPRUtility -information -listIssues -search -query "Analysis:!not an issue [fortify priority order]:!low" -categoryIssueCounts -project ./target/fortify/results.fpr > ./target/fortify/results.issues
            BIRTReportGenerator -template "PCI DSS Compliance" -filterSet "Quick View" -searchQuery Analysis:!"not an issue" -source ./target/fortify/results.fpr -format PDF --Version "3.2 Compliance" --SecurityIssueDetails false -output target/fortify/results.pdf
            curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/results.fpr https://nxrm.net.com/repository/fortify/${JOB_NAME}/commits/${GIT_COMMIT}/
            curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/results.issues https://nxrm.net.com/repository/fortify/${JOB_NAME}/commits/${GIT_COMMIT}/
            curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/results.pdf https://nxrm.net.com/repository/fortify/${JOB_NAME}/commits/${GIT_COMMIT}/

            curl --silent --show-error --fail --user "${userPass}" --output ./target/fortify/${DOT_JOB_NAME}.fpr \
                  -X GET https://nxrm.net.com/repository/fortify/${JOB_NAME}/${DOT_JOB_NAME}.fpr || echo "${DOT_JOB_NAME}.fpr does not exist. Nothing to merge."
            if [ -f "./target/fortify/${DOT_JOB_NAME}.fpr" ]; then
              FPRUtility -merge -project ./target/fortify/${DOT_JOB_NAME}.fpr -source ./target/fortify/results.fpr -f ./target/fortify/merged.fpr
              FPRUtility -information -listIssues -search -query "Analysis:!not an issue [fortify priority order]:!low" -categoryIssueCounts -project ./target/fortify/merged.fpr > ./target/fortify/merged.issues
              BIRTReportGenerator -template "PCI DSS Compliance" -filterSet "Quick View" -searchQuery Analysis:!"not an issue" -source ./target/fortify/merged.fpr -format PDF --Version "3.2 Compliance" --SecurityIssueDetails false -output target/fortify/merged.pdf
              # Upload fpr
              curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/merged.fpr https://nxrm.net.com/repository/fortify/${JOB_NAME}/commits/${GIT_COMMIT}/
              curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/merged.fpr https://nxrm.net.com/repository/fortify/${JOB_NAME}/${DOT_JOB_NAME}.fpr
              # Upload .issues
              curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/merged.issues https://nxrm.net.com/repository/fortify/${JOB_NAME}/commits/${GIT_COMMIT}/
              curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/merged.issues https://nxrm.net.com/repository/fortify/${JOB_NAME}/${DOT_JOB_NAME}.issues
              # Upload .pdf
              curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/merged.pdf https://nxrm.net.com/repository/fortify/${JOB_NAME}/commits/${GIT_COMMIT}/
              curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/merged.pdf https://nxrm.net.com/repository/fortify/${JOB_NAME}/${DOT_JOB_NAME}.pdf
            else
              curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/results.fpr https://nxrm.net.com/repository/fortify/${JOB_NAME}/${DOT_JOB_NAME}.fpr
              curl --silent --show-error --user "${userPass}" --upload-file ./target/fortify/results.issues https://nxrm.net.com/repository/fortify/${JOB_NAME}/${DOT_JOB_NAME}.issues
            fi
            '''
          } //withCredentials
          } //lock env.JOB_NAME+'/fortify'
        }
      }
    }
  }
  post {
    always {
      archiveArtifacts artifacts: '**/target/surefire-reports/*, **/target/*.exec, **/site/jacoco/**', fingerprint: true, allowEmptyArchive: true
      junit testResults: '**/target/surefire-reports/*.xml', allowEmptyResults: true
      script {
        jacoco(
          execPattern: '**/target/*.exec',
          classPattern: '**/target/classes',
          sourcePattern: '**/src/main/java',
          exclusionPattern: getValue(globalJsonSettings, 'jacocoExclusionPattern')
        )
        def subjectPrefix = ""
        if (!"production".equals(env.WHERE_AM_I)) {
          subjectPrefix = "[ ${env.WHERE_AM_I} ] "
        }
        emailTo = getValue(globalJsonSettings, 'emailTo')
        def subject = "${subjectPrefix}${currentBuild.result} -=-=- ${env.JOB_NAME} [${env.BUILD_NUMBER}] "
        def details = " ${env.BUILD_URL} ${env.JOB_NAME} [${env.BUILD_NUMBER}] "
        emailext (
          to: emailTo,
          recipientProviders: [[$class: 'CulpritsRecipientProvider'],
                              [$class: 'RequesterRecipientProvider']],
                              // [$class: 'DevelopersRecipientProvider']],
          subject: subject,
          body: details,
          attachLog: true
        )
      }
    }
    success {
      script {
        scheduleBuild(env.BRANCH_NAME, getValue(globalJsonSettings, 'scheduleFromDevelop'), getValue(globalJsonSettings, 'scheduleFromMaster'), '{"version":"'+readMavenPom().getVersion()+'"}' )
      }
    }
  }
}
} //call

fix this pipeline