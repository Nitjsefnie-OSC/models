#!/bin/bash
set -euo pipefail
if [[ "$#" == 5 && "$1" == s3 && "$2" == sync && "$3" == /work/out && "$4" == s3://models-qual-077051030817/results/activated-fixture-d2d0/ && "$5" == --only-show-errors ]]; then
 exec /usr/local/bin/aws s3 cp "$3" "$4" --recursive --only-show-errors
fi
exec /usr/local/bin/aws "$@"
