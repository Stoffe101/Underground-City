#!/usr/bin/env python3
"""Delete only explicitly inspected gallery artifact IDs with matching source commits.

GitHub-hosted workflow helper. Diagnostic/build artifacts never qualify.
"""
import json
import os
import re
import subprocess
from pathlib import Path


def api(path: str, delete: bool=False):
    command=['gh','api',path]
    if delete:command+=['--method','DELETE']
    output=subprocess.check_output(command,text=True)
    return json.loads(output) if output.strip() else None


def main():
    repository=os.environ['GITHUB_REPOSITORY']
    records=json.loads(Path('content/qa/reviewed-runs.json').read_text())['runs']
    targets=[]
    for record in records:
        if record.get('review_completed') is not True or not re.fullmatch(r'[0-9a-f]{40}',record['head_sha']):
            raise ValueError('Unconfirmed or invalid review record')
        run=int(record['run_id'])
        info=api(f'repos/{repository}/actions/runs/{run}')
        if info['head_sha']!=record['head_sha']:raise ValueError('Reviewed source commit does not match run')
        artifacts=api(f'repos/{repository}/actions/runs/{run}/artifacts?per_page=100')['artifacts']
        wanted={int(value) for value in record['artifact_ids']}
        for artifact in artifacts:
            if artifact['id'] in wanted:
                if not artifact['name'].startswith('uc-visual-gallery-') or artifact['workflow_run']['head_sha']!=record['head_sha']:
                    raise ValueError('Only the reviewed source gallery may be deleted')
                targets.append(artifact['id'])
    # Validate all reviews before any deletion. Missing IDs mean already cleaned.
    for artifact in set(targets):api(f'repos/{repository}/actions/artifacts/{artifact}',delete=True)
    print('Deleted',len(set(targets)),'reviewed galleries; retained diagnostics and builds.')


if __name__=='__main__':main()
