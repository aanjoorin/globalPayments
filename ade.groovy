def call() {
    pipeline {
        agent {
            // Your agent configuration
        }

        // Rest of your parameters and environment variables

        stages {
            stage('Clone and push') {
                steps {
                    script {
                        def repos = env.REPO_NAMES.split(',')
                        for (def repo in repos) {
                            git url: "${BB_URL}/${BB_PROJECTKEY}/${repo}.git", credentialsId: env.BB_APITOKEN
                            dir("${repo}") {
                                sh "git push --mirror ${GHE_URL}/${GHE_ORG}/${repo}.git --all --force"
                            }
                        }
                    }
                }
            }

            stage('Remove Users') {
                steps {
                    script {
                        def usersToRemove = ["user1", "user2", "user3"]
                        def auth = "${env.BB_APITOKEN}"
                        def bb_api_url = "$BB_HOSTAME/rest/api/1.0"
                        
                        for (def user in usersToRemove) {
                            for (def repo in repos) {
                                sh """
                                    curl -X DELETE -u ${auth} ${bb_api_url}/projects/${BB_PROJECTKEY}/repos/${repo}/permissions/users?name=${user}&permission=REPO_WRITE" | jq
                                """
                            }
                        }
                    }
                }
            }
        }
    }
}
