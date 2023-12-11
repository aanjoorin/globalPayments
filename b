stage('Plan Migration') {
  steps {
    container(name: 'kaniko', shell: '/busybox/sh') {
      script {
        withCredentials([file(credentialsId: 'devops.json', variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
          sh '''
            images=$(cat images.txt)
            touch toCopy.txt
            gcr="us.gcr.io/devops/third-party"
            gcrane ls ${gcr} || { echo "Unable to retrieve list of images" ; exit 1; }
            for img in ${images}
            do
              it=$(echo ${img#*/}) # Remove repo from path
              i=$(echo ${it%:*}) # Remove tag
              echo "Checking to make sure ${it} does not exist."
              existing_image=$(gcrane ls ${gcr}/${i} | grep -w "${gcr}/${it}")
              if [[ -z ${existing_image} ]]; then
                echo "Image ${it} does not exist. Adding to copy list..."
                echo ${img} >> toCopy.txt
              else
                existing_digest=$(echo ${existing_image} | awk '{print $1}') # Extracting the digest from the existing image information
                current_digest=$(gcrane describe ${gcr}/${it} | grep -w Digest | awk '{print $2}') # Extracting the digest of the current image
                if [[ ${existing_digest} != ${current_digest} ]]; then
                  echo "Image ${it} has been updated. Adding to copy list..."
                  echo ${img} >> toCopy.txt
                else
                  echo "Image ${it} exists and has not been updated. Skipping copy..."
                fi
              fi
            done
            echo "The following images will be copied to GCR upon merge to master:"
            cat toCopy.txt
          '''
        }
      }
    }
  }
}
