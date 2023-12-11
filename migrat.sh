#!/bin/bash

# ENV VARIABLES
BB_URL="ssh://git@derek.net.com:7999"
BB_HOSTNAME="https://derek.net.com/bitbucket"
GHE_URL="https://www.github.com"

# Bitbucket Environment Variables
BB_PROJECTKEY="your_bitbucket_project_key"
BB_USERNAME="your_bitbucket_username"
BB_APITOKEN="your_bitbucket_api_token"

# GitHub Environment Variables
GHE_USERNAME="your_github_username"
GHE_APITOKEN="your_github_api_token"
GHE_ORG="your_github_organization"

# Repositories to Process (comma-separated)
REPOS="repo1,repo2,repo3"

# Convert comma-separated list of repositories to an array
IFS=',' read -ra REPO_ARRAY <<< "$REPOS"

# Set exit on error and error message functions
set -e

function error_exit {
  echo "Error: $1"
  exit 1
}

for REPO in "${REPO_ARRAY[@]}"
do
  echo "Processing repository: $REPO"
  
  # CLONING BITBUCKET REPOSITORY
  echo "CLONING BITBUCKET REPOSITORY"
  git clone --bare "$BB_URL/$BB_PROJECTKEY/$REPO.git" || error_exit "Failed to clone $REPO"

  echo "Repository cloned: $REPO"

  # RENAMING BITBUCKET REPOSITORY
  echo "RENAMING BITBUCKET REPOSITORY"
  mv "$REPO.git" "$REPO"
  cd "$REPO"

  echo "Repository renamed: $REPO"

  # PUSHING TO GITHUB REPOSITORY
  echo "PUSHING TO GITHUB REPOSITORY"
  git push --mirror "$GHE_URL/$GHE_ORG/$REPO.git" || error_exit "Failed to push to GitHub repository"

  echo "Repository pushed to GitHub: $REPO"

  # GETTING LIST OF USERS WITH ACCESS TO BITBUCKET REPOSITORY
  echo "GETTING LIST OF USERS WITH ACCESS TO BITBUCKET REPOSITORY"
  USERS=$(curl -s -X GET -H "Authorization: Bearer ${BB_APITOKEN}" "$BB_HOSTNAME/rest/api/1.0/projects/${BB_PROJECTKEY}/repos/$REPO/permissions/users?permission=REPO_WRITE" | jq -r '.values[].user.slug')
  echo "USERS: $USERS"

  # DELETING WRITE ACCESS FOR USERS IN BITBUCKET REPOSITORY
  echo "DELETING WRITE ACCESS FOR USERS IN BITBUCKET REPOSITORY"
  for USER in $USERS
  do
    curl -X DELETE -H "Authorization: Bearer ${BB_APITOKEN}" -H "Content-Type: application/json" "$BB_HOSTNAME/rest/api/1.0/projects/${BB_PROJECTKEY}/repos/$REPO/permissions/users?name=$USER&permission=REPO_WRITE" | jq
  done

  echo "Write access deleted for users: $USERS"
  
  echo "END MIGRATION for repository: $REPO"
  echo ""
done;
