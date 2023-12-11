#!/bin/bash
# ENV VRIABLES
BB_URL="ssh://git@derek.net.com:7999"
BB_HOSTAME="https://derek.net.com/bitbucket"
GHE_URL="https://www.github.com"

# convert comma-separated list of users and repositories to arrays
IFS=',' read -ra USERS <<< "$USERS"
IFS=',' read -ra REPOS <<< "$REPOS"

for REPO in "${REPOS[@]}"
do
    echo ""
    # CLONING BITBUCKET REPOSITORY
    echo "CLONING BITBUCKET REPOSITORY"
    git clone --bare $BB_URL/$BB_PROJECTKEY/$REPO.git
    echo ""
    #RENAME BITBUCKET REPOSITORY
    echo "RENAMING BITBUCKET REPOSITORY"
    mv $REPO.git $REPO
    cd $REPO
    echo ""
# PUSHING TO GITHUB REPOSITORY
git push --mirror $GHE_URL/$GHE_ORG/$REPO.git
echo ""

    # GETTING LIST OF USERS WITH ACCESS TO BITBUCKET REPOSITORY
    echo "GETTING LIST OF USERS WITH ACCESS TO BITBUCKET REPOSITORY"
    USERS=$(curl -s -X GET -H "Authorization: Bearer ${BB_APITOKEN}" \
        "$BB_HOSTAME/rest/api/1.0/projects/${BB_PROJECTKEY}/repos/$REPO/permissions/users?permission=REPO_WRITE" | jq -r '.values[].user.slug')
    echo "USERS: $USERS"

    # # DELETING WRITE ACCESS FOR USERS IN BITBUCKET REPOSITORY
    echo "DELETING WRITE ACCESS FOR USERS IN BITBUCKET REPOSITORY"
    for USER in $USERS
  do
    curl -X DELETE -H "Authorization: Bearer ${BB_APITOKEN}" -H "Content-Type: application/json" \
    "$BB_HOSTAME/rest/api/1.0/projects/${BB_PROJECTKEY}/repos/$REPO/permissions/users?name=$USER&permission=REPO_WRITE" | jq
  done
echo ""
echo "END MIGRATION"
echo ""
done;


make this script a jenkinspipeline with different stages of cloning the bitbuckeet repo, renaming the cloned repo, push to github repository and getting list of users with write access and deleting users with write access