#!/bin/bash

# Set your GitHub Enterprise API token and organization here
GHE_APITOKEN="YOUR_GHE_API_TOKEN"
GHE_ORG="YOUR_ORGANIZATION"
GHE_URL="https://your-github-enterprise-url.com/api/v3"  # Update this URL

# Set the repository name here
REPO_NAME="your-repo-name"

# Get a list of branches in the repository
BRANCHES=$(curl -s -H "Authorization: Bearer $GHE_APITOKEN" \
    "$GHE_URL/repos/$GHE_ORG/$REPO_NAME/branches" | jq -r '.[].name')

# Loop through branches and delete README files
for BRANCH in $BRANCHES
do
    # Delete README file using GitHub API
    DELETE_RESPONSE=$(curl -X DELETE -s -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer $GHE_APITOKEN" \
        "$GHE_URL/repos/$GHE_ORG/$REPO_NAME/contents/README.md?ref=$BRANCH")

    if [ "$DELETE_RESPONSE" == "204" ]; then
        echo "Deleted README file from branch $BRANCH"
    else
        echo "Failed to delete README file from branch $BRANCH"
    fi
done
