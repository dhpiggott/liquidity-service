#!/bin/bash

set -euo pipefail

sbt ";server/it:scalafmt::test ;server/docker:publishLocal ;server/it:test
  ;server/docker:clean"
