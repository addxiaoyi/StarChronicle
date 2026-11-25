"""Prepare the real ordinary-member review scenario in an isolated Paper save."""
from pathlib import Path
import argparse
import importlib.util
import re

SPEC = importlib.util.spec_from_file_location('epic_prepare', Path(__file__).with_name('prepare-epic-paper.py'))
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)
ROOT = Path(__file__).resolve().parents[1]

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--attempt', default='member-review-1', type=lambda value: value if re.fullmatch(r'[a-z0-9-]{1,32}', value) else parser.error('Invalid attempt'))
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--product', type=Path, default=ROOT / 'target/starchronicle-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--starcore', type=Path, required=True)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    args.route = 'DUNGEON'
    PREPARE.prepare(args)
    args.run = ROOT / 'run' / f'paper-epic-{PREPARE.sha(args.product)[:8]}-dungeon-{args.attempt}'
    PREPARE.write(args.run / 'plugins/StarChronicle/stories/member-review.yml', '''id: member-review
version: "1"
title: "成员复核验收"
scope: WORLD
triggers: [MEMBER_REVIEW_FIXTURE]
start: review
cooldownSeconds: 0
nodes:
  review:
    text: "核验当前参与资格"
    quorum: 2
    choices:
      yes: {label: "继续", next: closing}
  closing:
    text: "记录参与结果"
    quorum: 2
    choices:
      yes: {label: "完成", next: end}
  end:
    text: "历史保留"
    terminal: true
    effects:
      - {id: member-review-history, type: history, target: MEMBER_REVIEW_SETTLED, value: "成员复核验收完成"}
''')
    PREPARE.compile_fixture(args)
