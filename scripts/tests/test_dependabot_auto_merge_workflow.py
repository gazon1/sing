"""Tests for the "Dependabot auto-merge" workflow.

The workflow decides, on its own, which dependabot pull requests may merge
themselves: a candidate that is a patch or a minor bump and whose own `CI gate`
check-run is green gets squashed, everything else is left alone and explained
in the job summary.

Two properties of that decision are worth pinning, because both of them were
once wrong in a way that was invisible until a pull request was merged or a
whole batch died:

* a conflicted pull request (`.mergeable_state == "dirty"`) is EXCLUDED — it is
  reported as a warning, it is named in the "Excluded — conflicts" group of the
  summary, and it is never handed to `gh pr merge` (which answers 405 and takes
  the whole run down with it);
* one refused merge does not stop the others — the failure is recorded, the
  later pull requests are still merged, and the step reports the failure at the
  end instead of aborting on the first one.

The workflow's shell lives inside YAML, so the tests extract the `run:` script
of a named step and run it with bash against a throwaway directory: a fake `gh`
on PATH answers the API calls the workflow actually makes, a stand-in for `jq`
answers the two filters the workflow applies, and the clock is a fake.

The stand-in for `jq` is installed on every machine, not only on one that has
no `jq` of its own: the workflow calls `jq -r '<filter>'`, and a stand-in that
only ever works where the real `jq` is missing is a stand-in nobody runs. The
fake `gh` looks its own `jq` up in the system directories alone, so the filter
it hands to it is still evaluated by the real `jq` when the machine has one.

The clock is a fake for a reason: the `checks` step polls until a deadline of
thirty minutes, sleeping twenty seconds a round, so a pull request whose gate
never turns green would otherwise keep the suite running for half an hour. The
fake `date` jumps past that deadline on its second call, and every step runs
under a subprocess timeout, so a workflow that stops honouring its own deadline
fails the test in seconds instead of hanging it.

Nothing here touches the network or the real `gh`: the stub directory comes
first on PATH, and the fake `gh` only answers the two API URLs and the one
merge the workflow performs.

The properties asserted here:

* a conflicted pull request is excluded and reported, and the run still ends
  successfully;
* a green pull request is merged, with the head commit that was verified pinned
  by `--match-head-commit`;
* a pull request whose check-run is not green is reported as waiting and is
  never handed to `gh pr merge`;
* a run in which every candidate is conflicted says so — the "Merged" group
  reads "none" instead of being silent;
* a run in which every candidate was excluded by the kind of its bump still
  writes a summary, even though the steps that merge are skipped;
* the step that writes the summary is the last step of the job and it runs
  unconditionally — the guarantee the run above depends on;
* a refused merge is recorded and does not stop the merges that come after it,
  and the step exits non-zero.
"""

import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
WORKFLOW = REPO_ROOT / '.github' / 'workflows' / 'dependabot-auto-merge.yml'

#: The repository the workflow is asked about. The extracted script carries the
#: literal `${{ github.repository }}`, which the test replaces before running it.
REPOSITORY = 'acme/widgets'

#: The PATH the workflow runs under: the stubs first, then the system
#: directories the shell needs (`env`, `bash`, `jq`, `tr`, `cp`).
SYSTEM_PATH = '/usr/local/bin:/usr/bin:/bin'

#: How long a single step may take. The real `checks` step polls for thirty
#: minutes; the fake clock ends that in milliseconds. A step that runs past
#: this is a step that lost its deadline, and the test says so instead of
#: hanging the suite.
STEP_TIMEOUT = 60

#: The stand-in for `gh`, for the two API calls the workflow makes and the one
#: merge it performs. The state lives in files under $FAKE_GH_STATE, so a test
#: can change the world between calls.
FAKE_GH = '''#!/usr/bin/env python3
"""A stand-in for `gh`, for the calls the workflow makes.

* `gh api repos/OWNER/REPO/pulls/N` answers with the pull request: its
  `.mergeable_state` and its `.head.sha`;
* `gh api repos/OWNER/REPO/commits/SHA/check-runs?per_page=100` answers with
  the check-runs of that commit, filtered by the workflow's own `--jq`
  expression;
* `gh pr merge N --squash --delete-branch --match-head-commit SHA` is recorded
  and succeeds unless the test said that this pull request is refused.
"""

import json
import os
import subprocess
import sys

STATE = os.environ['FAKE_GH_STATE']


def _read(path, default=''):
    try:
        with open(path, encoding='utf-8') as handle:
            return handle.read().strip()
    except FileNotFoundError:
        return default


def _pull_request(number):
    state = _read(os.path.join(STATE, 'pulls', number + '.json'))
    return json.loads(state) if state else {}


def _check_runs(sha):
    conclusion = _read(os.path.join(STATE, 'checks', sha + '.conclusion'), 'success')
    if conclusion == 'none':
        return {'check_runs': []}
    return {'check_runs': [{'id': 1, 'name': 'CI gate', 'conclusion': conclusion}]}


def _apply_jq(expression, payload):
    """The `--jq` of `gh api`, applied the way `gh` applies it."""
    text = json.dumps(payload)
    # The real `jq`, not the stand-in: the stand-in is there for the workflow's
    # own filters, and this filter is one the stand-in does not know.
    environment = dict(os.environ)
    environment['PATH'] = os.environ.get('FAKE_GH_SYSTEM_PATH', environment.get('PATH', ''))
    try:
        jq = subprocess.run(
            ['jq', '-r', expression],
            input=text,
            capture_output=True,
            text=True,
            env=environment,
        )
    except FileNotFoundError:
        jq = None
    if jq is not None and jq.returncode == 0:
        return jq.stdout
    # No `jq` on the machine: the filter the workflow uses here asks for the
    # conclusion of the last check-run named "CI gate".
    runs = sorted(
        (run for run in payload.get('check_runs', []) if run.get('name') == 'CI gate'),
        key=lambda run: run.get('id', 0),
    )
    if not runs:
        return 'missing\\n'
    conclusion = runs[-1].get('conclusion')
    return (conclusion or 'pending') + '\\n'


def main(argv):
    if not argv:
        return 2
    if argv[0] == 'api':
        url = argv[1]
        expression = None
        rest = argv[2:]
        while rest:
            if rest[0] == '--jq' and len(rest) > 1:
                expression = rest[1]
                break
            rest = rest[1:]
        if '/check-runs' in url:
            sha = url.split('/commits/', 1)[1].split('/check-runs', 1)[0]
            payload = _check_runs(sha)
        elif '/pulls/' in url:
            number = url.rsplit('/pulls/', 1)[1].split('?', 1)[0]
            payload = _pull_request(number)
        else:
            sys.stderr.write('fake gh: unexpected url %s\\n' % url)
            return 2
        if expression is None:
            sys.stdout.write(json.dumps(payload) + '\\n')
        else:
            sys.stdout.write(_apply_jq(expression, payload))
        return 0
    if argv[0] == 'pr' and len(argv) > 1 and argv[1] == 'merge':
        number = argv[2]
        with open(os.path.join(STATE, 'merge_calls.log'), 'a', encoding='utf-8') as log:
            log.write(' '.join(argv) + '\\n')
        if os.path.exists(os.path.join(STATE, 'merge_refused', number)):
            sys.stderr.write('fake gh: the merge of #%s was refused\\n' % number)
            return 1
        return 0
    sys.stderr.write('fake gh: unsupported call %r\\n' % (argv,))
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
'''

#: The stand-in for `date`. The polling loop of the `checks` step waits for the
#: CI gate to turn green until a deadline of thirty minutes, sleeping twenty
#: seconds a round. The first call is the deadline itself; every later call is
#: already past it, so the loop gives up on its second round instead of its
#: ninetieth.
FAKE_DATE = '''#!/usr/bin/env bash
set -euo pipefail
state="$FAKE_DATE_STATE"
count=0
if [ -f "$state" ]; then
  count="$(cat "$state")"
fi
count=$((count + 1))
printf '%s' "$count" > "$state"
if [ "$count" -le 1 ]; then
  echo 1000000
else
  echo 1003600
fi
'''

#: The stand-in for `jq`. The workflow calls it as `jq -r '<filter>'`, so the
#: filter is the last argument and the flags before it are ignored. It
#: understands the two filters the workflow applies to the pull request.
MINI_JQ = '''#!/usr/bin/env python3
import json
import sys

arguments = [argument for argument in sys.argv[1:] if not argument.startswith('-')]
if not arguments:
    sys.stderr.write('mini jq: no filter\\n')
    sys.exit(2)
expression = arguments[-1]
payload = json.load(sys.stdin)
if expression.startswith('.mergeable_state'):
    print(payload.get('mergeable_state') or 'unknown')
elif expression.startswith('.head.sha'):
    print((payload.get('head') or {}).get('sha', ''))
else:
    sys.stderr.write('mini jq: unsupported expression %s\\n' % expression)
    sys.exit(2)
'''


def _steps(lines):
    """The steps of the workflow's single job, as blocks of lines."""
    start = None
    for index, line in enumerate(lines):
        if re.match(r'^ {4}steps:\s*$', line):
            start = index
            break
    if start is None:
        raise AssertionError('the workflow has no steps')

    entries = []
    indent = None
    for index in range(start + 1, len(lines)):
        match = re.match(r'^( +)- ', lines[index])
        if match is None:
            continue
        if indent is None:
            indent = len(match.group(1))
        if len(match.group(1)) == indent:
            entries.append(index)
    if not entries:
        raise AssertionError('the workflow has no steps')

    blocks = []
    for position, entry in enumerate(entries):
        end = entries[position + 1] if position + 1 < len(entries) else len(lines)
        blocks.append(lines[entry:end])
    return blocks


def _key(block, key, indent):
    pattern = re.compile(r'^ {%d}%s: (.*)$' % (indent, key))
    for line in block:
        match = pattern.match(line)
        if match:
            return match.group(1).strip()
    return None


def _step_name(block):
    """The name of a step: it sits on the `- name:` line, not on its own key."""
    match = re.match(r'^\s*- name: (.*)$', block[0])
    return match.group(1).strip() if match else None


def _run_script(block, indent):
    """The `run:` script of a step, as a shell script."""
    pattern = re.compile(r'^ {%d}run: (.*)$' % indent)
    for position, line in enumerate(block):
        match = pattern.match(line)
        if not match:
            continue
        if match.group(1).strip() not in ('|', '|-', '|+'):
            raise AssertionError('the run block is not a literal: %r' % match.group(1))
        body = []
        for line in block[position + 1:]:
            if line.strip() and len(line) - len(line.lstrip()) <= indent:
                break
            body.append(line)
        while body and not body[-1].strip():
            body.pop()
        if not body:
            raise AssertionError('the run block is empty')
        strip = len(body[0]) - len(body[0].lstrip())
        return '\n'.join(line[strip:] if line.strip() else '' for line in body) + '\n'
    raise AssertionError('the step has no run block')


def _step_block(name=None, step_id=None):
    """The block of one step of the workflow, and the indent of its keys."""
    lines = WORKFLOW.read_text(encoding='utf-8').splitlines()
    for block in _steps(lines):
        step_indent = len(block[0]) - len(block[0].lstrip()) + 2
        if name is not None and _step_name(block) != name:
            continue
        if step_id is not None and _key(block, 'id', step_indent) != step_id:
            continue
        if name is None and step_id is None:
            raise AssertionError('a step needs a name or an id')
        return block, step_indent
    raise AssertionError('no step named %r with id %r' % (name, step_id))


def _step_script(name=None, step_id=None):
    """The script of one step of the workflow, with the repository filled in."""
    block, step_indent = _step_block(name=name, step_id=step_id)
    script = _run_script(block, step_indent)
    return script.replace('${{ github.repository }}', REPOSITORY)


def _step_condition(block, step_indent):
    """The `if:` of a step, or None when it runs unconditionally."""
    return _key(block, 'if', step_indent)


def _summary_group(summary, heading):
    """The lines of one `###` group of the job summary."""
    lines = summary.splitlines()
    try:
        start = lines.index(heading)
    except ValueError:
        raise AssertionError('the summary has no group %r:\n%s' % (heading, summary))
    group = []
    for line in lines[start + 1:]:
        if line.startswith('### '):
            break
        group.append(line)
    return [line.strip() for line in group if line.strip()]


class DependabotAutoMergeWorkflowTest(unittest.TestCase):
    """The workflow excludes the pull requests it must not merge, and merges the rest."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='dependabot-auto-merge-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.stubs = self.tmp / 'stubs'
        self.stubs.mkdir()
        self.state = self.tmp / 'gh-state'
        (self.state / 'pulls').mkdir(parents=True)
        (self.state / 'checks').mkdir(parents=True)
        (self.state / 'merge_refused').mkdir(parents=True)
        self.runner_temp = self.tmp / 'runner-temp'
        self.runner_temp.mkdir()
        self.summary = self.runner_temp / 'step-summary.md'
        self.summary.write_text('', encoding='utf-8')
        # The decisions table of the repository is not the subject of these
        # tests, and a decision recorded for one of the dependencies would
        # change what the summary says. The table the step reads is a
        # throwaway one with no rows in it.
        self.decisions = self.tmp / 'dependency-decisions.tsv'
        self.decisions.write_text('', encoding='utf-8')

        self._write_stub('gh', FAKE_GH)
        self._write_stub('date', FAKE_DATE)
        # The stand-in for `jq` is installed on every machine, so the workflow's
        # own filters are exercised here and not only where `jq` happens to be
        # missing.
        self._write_stub('jq', MINI_JQ)

    def _write_stub(self, name: str, body: str) -> None:
        stub = self.stubs / name
        stub.write_text(body, encoding='utf-8')
        stub.chmod(0o755)

    def _pull_request(self, number: str, mergeable_state: str, head_sha: str) -> None:
        payload = {'mergeable_state': mergeable_state, 'head': {'sha': head_sha}}
        (self.state / 'pulls' / f'{number}.json').write_text(
            json.dumps(payload), encoding='utf-8'
        )

    def _check_run(self, sha: str, conclusion: str) -> None:
        (self.state / 'checks' / f'{sha}.conclusion').write_text(conclusion, encoding='utf-8')

    def _refuse_the_merge_of(self, number: str) -> None:
        (self.state / 'merge_refused' / number).write_text('refused', encoding='utf-8')

    def _merge_calls(self):
        log = self.state / 'merge_calls.log'
        if not log.exists():
            return []
        return [line for line in log.read_text(encoding='utf-8').splitlines() if line]

    def _outputs(self) -> dict:
        """The step outputs the workflow wrote to `$GITHUB_OUTPUT`."""
        path = self.tmp / 'github-output'
        if not path.exists():
            return {}
        outputs = {}
        for line in path.read_text(encoding='utf-8').splitlines():
            name, _, value = line.partition('=')
            outputs[name.strip()] = value.strip()
        return outputs

    def _run_step(self, script: str, cwd: pathlib.Path | None = None) -> subprocess.CompletedProcess:
        path = self.tmp / 'step.sh'
        path.write_text(script, encoding='utf-8')
        env = dict(os.environ)
        env['PATH'] = f'{self.stubs}:{SYSTEM_PATH}'
        env['RUNNER_TEMP'] = str(self.runner_temp)
        env['GITHUB_STEP_SUMMARY'] = str(self.summary)
        env['GITHUB_OUTPUT'] = str(self.tmp / 'github-output')
        # The decisions table the `bump` step reads: a throwaway one, so the
        # repository's own table cannot change what the summary says.
        env['DEPENDENCY_DECISIONS'] = str(self.decisions)
        env['FAKE_GH_STATE'] = str(self.state)
        env['FAKE_DATE_STATE'] = str(self.tmp / 'date-state')
        # The fake `gh` looks its `jq` up in the system directories alone: the
        # stub directory holds the stand-in for the workflow's own filters.
        env['FAKE_GH_SYSTEM_PATH'] = SYSTEM_PATH
        try:
            return subprocess.run(
                ['bash', str(path)],
                cwd=str(cwd or self.tmp),
                capture_output=True,
                text=True,
                env=env,
                check=False,
                timeout=STEP_TIMEOUT,
            )
        except subprocess.TimeoutExpired:
            self.fail(
                'the step did not finish in %d seconds: it is polling past its own deadline'
                % STEP_TIMEOUT
            )

    def _run_summary_step(self) -> str:
        """The step that writes the summary, and the summary it wrote."""
        result = self._run_step(_step_script(name='What happened to every pull request'))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return self.summary.read_text(encoding='utf-8')

    def _candidates(self, *numbers: str) -> None:
        """The pull requests the `bump` step handed on as mergeable."""
        (self.runner_temp / 'mergeable.txt').write_text(
            ''.join(f'{number}\n' for number in numbers), encoding='utf-8'
        )
        (self.runner_temp / 'skipped.txt').write_text('', encoding='utf-8')

    def _write_candidate(self, number: str, body: str) -> None:
        """A candidate the `bump` step reads: the list and its description."""
        (self.runner_temp / 'candidates.txt').write_text(f'{number}\n', encoding='utf-8')
        (self.runner_temp / f'description-{number}.md').write_text(body, encoding='utf-8')

    def test_a_conflicted_pull_request_is_excluded_and_reported(self):
        """A conflicted pull request is named in the summary and never merged."""
        self._pull_request('12', 'dirty', 'sha-conflicted')
        self._check_run('sha-conflicted', 'success')
        self._candidates('12')

        checks = self._run_step(_step_script(step_id='checks'))
        self.assertEqual(0, checks.returncode, checks.stdout + checks.stderr)

        merge = self._run_step(_step_script(name='Merge it'))
        self.assertEqual(0, merge.returncode, merge.stdout + merge.stderr)

        self.assertEqual([], self._merge_calls(), 'a conflicted pull request was merged')
        self.assertIn('::warning::#12 has conflicts', checks.stdout)
        summary = self._run_summary_step()
        conflicts = _summary_group(summary, '### Excluded — conflicts')
        self.assertIn('- #12 has conflicts and cannot be merged', conflicts)

    def test_a_green_pull_request_is_merged_at_the_verified_head_commit(self):
        """The merge is pinned to the head commit whose check-run was green."""
        self._pull_request('7', 'clean', 'sha-green')
        self._check_run('sha-green', 'success')
        self._candidates('7')

        checks = self._run_step(_step_script(step_id='checks'))
        self.assertEqual(0, checks.returncode, checks.stdout + checks.stderr)

        merge = self._run_step(_step_script(name='Merge it'))
        self.assertEqual(0, merge.returncode, merge.stdout + merge.stderr)

        self.assertEqual(
            ['pr merge 7 --squash --delete-branch --match-head-commit sha-green'],
            self._merge_calls(),
        )
        merged = _summary_group(self._run_summary_step(), '### Merged')
        self.assertIn('- #7 (sha-green)', merged)

    def test_a_pull_request_whose_check_run_is_not_green_is_waiting(self):
        """A check-run that is not `success` leaves the pull request waiting."""
        self._pull_request('9', 'clean', 'sha-red')
        self._check_run('sha-red', 'failure')
        self._candidates('9')

        checks = self._run_step(_step_script(step_id='checks'))

        # The run gives up on the deadline rather than merging an un-green pull
        # request, and the summary says which one it was waiting for.
        self.assertNotEqual(0, checks.returncode, checks.stdout + checks.stderr)
        waiting = _summary_group(self._run_summary_step(), '### Waiting for a green CI gate')
        self.assertIn('- #9', waiting)

        # Nothing was handed to `gh pr merge`: the merge list was never written.
        self.assertFalse(
            (self.runner_temp / 'merge-now.txt').exists(),
            'a waiting pull request was put in the merge list',
        )
        self.assertEqual([], self._merge_calls(), 'a waiting pull request was merged')

    def test_a_run_in_which_every_candidate_is_conflicted_says_so(self):
        """The "Merged" group reads "none" — a run that merged nothing is not silent."""
        self._pull_request('21', 'dirty', 'sha-a')
        self._pull_request('22', 'dirty', 'sha-b')
        self._candidates('21', '22')

        checks = self._run_step(_step_script(step_id='checks'))
        self.assertEqual(0, checks.returncode, checks.stdout + checks.stderr)

        merge = self._run_step(_step_script(name='Merge it'))
        self.assertEqual(0, merge.returncode, merge.stdout + merge.stderr)

        self.assertEqual([], self._merge_calls())
        summary = self._run_summary_step()
        self.assertEqual(['- none'], _summary_group(summary, '### Merged'))
        conflicts = _summary_group(summary, '### Excluded — conflicts')
        self.assertIn('- #21 has conflicts and cannot be merged', conflicts)
        self.assertIn('- #22 has conflicts and cannot be merged', conflicts)

    def test_the_summary_step_runs_even_when_the_steps_before_it_are_skipped(self):
        """The step that writes the summary is the last one, and it always runs.

        The steps before it are conditional — the poll is skipped when no
        candidate was a patch or a minor bump, the merge is skipped when the
        poll did not succeed — and a skipped step writes no summary. The
        summary step therefore has to be unconditional, and it has to come
        after the steps whose files it reads.
        """
        lines = WORKFLOW.read_text(encoding='utf-8').splitlines()
        blocks = _steps(lines)
        last = blocks[-1]
        self.assertEqual(
            'What happened to every pull request',
            _step_name(last),
            'the summary step is not the last step of the job',
        )
        indent = len(last[0]) - len(last[0].lstrip()) + 2
        self.assertEqual(
            'always()',
            _step_condition(last, indent),
            'the summary step is conditional, so a skipped step before it takes the summary away',
        )

    def test_a_run_in_which_every_candidate_is_excluded_says_why(self):
        """A run that merged nothing because of the kind of bump says so.

        The `checks` and `Merge it` steps are skipped when no candidate was a
        patch or a minor bump, so the summary has to come from a step that
        always runs. This is the run that used to finish successfully and say
        nothing about why.
        """
        # The `bump` step itself: a candidate that is neither a patch nor a
        # minor is excluded, and the step hands on nothing.
        self._write_candidate('41', 'Bumps actions/setup-java from 5.7.0 to 6.1.0.')
        bump = self._run_step(_step_script(step_id='bump'), cwd=REPO_ROOT)
        self.assertEqual(0, bump.returncode, bump.stdout + bump.stderr)
        self.assertEqual('', (self.runner_temp / 'mergeable.txt').read_text(encoding='utf-8'))
        skipped = (self.runner_temp / 'skipped.txt').read_text(encoding='utf-8')
        self.assertIn('41', skipped, 'the excluded pull request was not recorded')

        # The count is what the steps after it are conditioned on: a count of
        # zero is what skips them, and this run is that case.
        self.assertEqual('0', self._outputs()['count'])
        checks, indent = _step_block(step_id='checks')
        self.assertIn("steps.bump.outputs.count != '0'", _step_condition(checks, indent) or '')

        # The steps that follow are skipped, and the summary still says why.
        summary = self._run_summary_step()
        excluded = _summary_group(summary, '### Excluded — not a patch or a minor bump')
        self.assertIn('- #41', excluded[0])
        self.assertIn('no decision is recorded', excluded[0])
        self.assertEqual(['- none'], _summary_group(summary, '### Merged'))

    def test_a_refused_merge_does_not_stop_the_merges_that_follow_it(self):
        """One refused merge is recorded, the next pull request is still merged."""
        self._pull_request('31', 'clean', 'sha-one')
        self._pull_request('32', 'clean', 'sha-two')
        self._check_run('sha-one', 'success')
        self._check_run('sha-two', 'success')
        self._refuse_the_merge_of('31')
        self._candidates('31', '32')

        checks = self._run_step(_step_script(step_id='checks'))
        self.assertEqual(0, checks.returncode, checks.stdout + checks.stderr)

        merge = self._run_step(_step_script(name='Merge it'))
        self.assertNotEqual(0, merge.returncode, merge.stdout + merge.stderr)

        self.assertEqual(
            [
                'pr merge 31 --squash --delete-branch --match-head-commit sha-one',
                'pr merge 32 --squash --delete-branch --match-head-commit sha-two',
            ],
            self._merge_calls(),
            'the merge that follows a refused one was never attempted',
        )
        summary = self._run_summary_step()
        merged = _summary_group(summary, '### Merged')
        self.assertIn('- #32 (sha-two)', merged)
        refused = _summary_group(summary, '### Not merged — the merge was refused')
        self.assertIn('- #31 (sha-one)', refused)


if __name__ == '__main__':
    unittest.main()
