          if ( getValue(imageJsonSettings, 'pushToGCP') == 'true' ) {
            withCredentials([file(credentialsId: 'dbox1.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
              println('Pushing to Sandbox: dbox1')
              sh '''
                gcrane push /image.tar us.gcr.io/dbox1/${IMAGE_NAME}:${IMAGE_TAG}
                gcrane tag us.gcr.io/dbox1/${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_TAG}-${GIT_COMMIT_HASH}
              '''
            }
            withCredentials([file(credentialsId: 'come.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
              println('Pushing to Develop: come')
              sh '''
                gcrane push /image.tar us.gcr.io/come/${IMAGE_NAME}:${IMAGE_TAG}
                gcrane tag us.gcr.io/come/${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_TAG}-${GIT_COMMIT_HASH}
              '''
            }




stage('Image Build & Push ') {
  steps {
    container(name: 'kaniko', shell: '/busybox/sh') {
      script {
        // function to build & push
        buildImages(globalJsonSettings)
        def imageFullName = "${imageLocation}/${imageName}"
        sh "echo ${GCLOUD_SERVICE_KEY} > /kaniko/.docker/key.json"
        withCredentials([file(credentialsId: 'net-sand.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
          sh '''
            docker build -t ${imageFullName} .
            gcrane push /image.tar us.gcr.io/net-sand/${imageName}:${imageTag}
            gcrane tag us.gcr.io/net-sand/${imageName}:${imageTag} ${imageName}:${imageTag} ${imageTag}-${GIT_COMMIT_HASH}
          '''  
        }
      }
    }
  }
}
