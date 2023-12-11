pipeline {
  agent any

  stages {
    stage('checkout')
      steps {
        checkout scm
      }
  }
  stage('RUn Script')
      steps {
        sh "chmod +x migration.sh"
        sh "./migration.sh"
      }
  }
}