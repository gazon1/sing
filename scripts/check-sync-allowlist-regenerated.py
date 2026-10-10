#!/usr/bin/env python3
"""
Gate: fails if supabase/migrations/sync_field_allowlist_seed.sql differs from
what would be generated from SyncContract.FIELD_ALLOWLIST today.

Run standalone: python3 scripts/check-sync-allowlist-regenerated.py
Run self-test:  python3 scripts/check-sync-allowlist-regenerated.py --self-test
"""
import argparse, subprocess, sys, os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(SCRIPT_DIR)  # project root
GEN_SCRIPT = os.path.join(ROOT, "infra/gen_sync_allowlist_sql.py")
OUTPUT_FILE = os.path.join(ROOT, "supabase/migrations/sync_field_allowlist_seed.sql")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Assert that the gate correctly fails when the seed is stale",
    )
    args = parser.parse_args()

    # Read current committed file
    with open(OUTPUT_FILE) as f:
        before = f.read()

    if args.self_test:
        # Mutate the seed to make it stale: add a field that does not exist in the
        # generator's output. The gate will see a diff and must exit non-zero.
        with open(OUTPUT_FILE, "w") as f:
            f.write(before + '    --self-test-probe-field text,\n')

    # Regenerate
    result = subprocess.run(
        ["python3", GEN_SCRIPT, ROOT],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        print(f"FAIL: generator script exited {result.returncode}")
        print(result.stderr)
        sys.exit(1)

    with open(OUTPUT_FILE) as f:
        after = f.read()

    # Restore original content so the self-test leaves no trace
    with open(OUTPUT_FILE, "w") as f:
        f.write(before)

    if before != after:
        print("FAIL: sync_field_allowlist_seed.sql is stale — run:")
        print(f"  python3 {GEN_SCRIPT} {ROOT}")
        print(f"  then commit the change")
        sys.exit(1)

    print("PASS: sync_field_allowlist_seed.sql is up to date with SyncContract.FIELD_ALLOWLIST")
    sys.exit(0)


if __name__ == "__main__":
    main()
