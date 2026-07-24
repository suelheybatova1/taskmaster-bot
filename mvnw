#!/bin/sh
# -----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information.
# The ASF licenses this file to you under the Apache License, Version 2.0.
# -----------------------------------------------------------------------------
set -euf

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_PROPERTIES="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
MAVEN_URL=$(sed -n 's/^distributionUrl=//p' "$WRAPPER_PROPERTIES")
MAVEN_VERSION=$(basename "$MAVEN_URL" -bin.zip | sed 's/apache-maven-//')
MAVEN_HOME_DIR="${MAVEN_USER_HOME:-${HOME}/.m2}/wrapper/dists/apache-maven-${MAVEN_VERSION}"
MAVEN_BIN="$MAVEN_HOME_DIR/apache-maven-${MAVEN_VERSION}/bin/mvn"

if [ ! -x "$MAVEN_BIN" ]; then
  ARCHIVE="$MAVEN_HOME_DIR/apache-maven-${MAVEN_VERSION}-bin.zip"
  mkdir -p "$MAVEN_HOME_DIR"
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --output "$ARCHIVE" "$MAVEN_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget --output-document="$ARCHIVE" "$MAVEN_URL"
  else
    echo "Error: curl or wget is required to download Maven." >&2
    exit 1
  fi
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ARCHIVE" -d "$MAVEN_HOME_DIR"
  else
    echo "Error: unzip is required to install Maven." >&2
    exit 1
  fi
  rm -f "$ARCHIVE"
fi

exec "$MAVEN_BIN" "$@"
