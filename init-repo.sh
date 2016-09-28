#!/usr/bin/env bash
#
# Script to initialize new/s/leak repo
# - install required node packages


node=`which node 2>&1`
if [ $? -ne 0 ]; then
  echo "Please install NodeJS."
  echo "http://nodejs.org/"
  exit 1
fi

npm=`which npm 2>&1`
if [ $? -ne 0 ]; then
  echo "Please install NPM."
fi

echo "Installing required npm packages..."
npm install

# Download custom visjs build
wget -O dist.zip https://www.dropbox.com/s/wnqno2a5b43ccaq/dist.zip?dl=0
# Overwrite visjs
unzip -d ./app/assets/javascripts/libs/vis/ -o dist.zip
rm dist.zip
