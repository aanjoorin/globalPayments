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
  
 
  stage('Prisma Cloud Image Scan') {
      steps {
          script {
              // Assuming you have already built and pushed the Docker image
              def imageName = 'your-docker-image-name:latest'
              
              // Run Prisma Cloud image scan
              prismaCloudScan (
                  imageName: imageName,
                  cloudConfig: 'my-prisma-cloud-config'
              )
  
              // Check image scan results for vulnerabilities
              def scanResults = prismaCloudGetScanResults imageName: imageName
              if (scanResults.vulnerabilities) {
                  error("Prisma Cloud image scan detected vulnerabilities. Failing the build.")
              } else {
                  echo "Prisma Cloud image scan passed. No vulnerabilities found."
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
                # use maven to generate a list of artifacts for the modules and filter them so we just get the war and jar artifacts
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
                      java -jar ${ARTIFACT} --run-type=adminreadmecli --command=describe | awk '/\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*/{p=1}p\' > adminreadme.txt || echo
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
                      java -jar ${ARTIFACT} --run-type=configcli --command=describe --file-type=bootstrap | awk '/\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*\\*/{p=1}p' > bootstrap.properties || echo
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
} //call
 fix this pipeline
