#!/bin/bash

POSITIONAL=()
while [[ $# -gt 0 ]]; do
    key="$1"

    case $key in
        -u|--bb_username)
        BB_USERNAME="$2"
        shift # past argument
        shift # past value
        ;;
        -a|--bb_apitoken)
        BB_APITOKEN="$2"
        shift # past argument
        shift # past value
        ;;
        -p|--bb_projectkey)
        BB_PROJECTKEY="$2"
        shift # past argument
        shift # past value
        ;;
        -v|--ghe_username)
        GHE_USERNAME="$2"
        shift # past argument
        shift # past value
        ;;
        -o|--ghe_org)
        GHE_ORG="$2"
        shift # past argument
        shift # past value
        ;;
        -t|--ghe_apitoken)
        GHE_APITOKEN="$2"
        shift # past argument
        shift # past value
        ;;
        -r|--repos)
        REPOS=("$2")
        shift # past argument
        shift # past value
        ;;
        -*|--*)
        echo "Unknown option $1"
        exit 1
        ;;
        *)
        POSITIONAL+=("$1") # save positional args
        shift # past argument
        ;;
    esac
done
set -- "${POSITIONAL[@]}" # restore positional parameters

# check if all required parameters have been provided
if [ -z "$BB_USERNAME" ] || [ -z "$BB_APITOKEN" ] || [ -z "$BB_PROJECTKEY" ] || \
   [ -z "$GHE_USERNAME" ] || [ -z "$GHE_ORG" ] || [ -z "$GHE_APITOKEN" ] || \
   [ -z "${REPOS[*]}" ]
then
    echo "Usage: migrateRepos.sh -u <bb_username> -a <bb_apitoken> -p <bb_projectkey> -v <ghe_username> -o <ghe_org> -t <ghe_apitoken> -r <repo1,repo2,repo3>"
    exit 1
fi

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

i want to run this script with a jenkins job ./move-script.sh how do I parse in the parameters