#!/bin/bash
# 기운차 시연 영상 자동 녹화 (v0.3, 캐릭터 모드 기준 약 93초)
# 누를 곳을 화면 글자로 찾는다. 먼저 좌표를 찾고(--find), 그 좌표로 녹화한다(--record).
# 사용: scripts/record_demo.sh [출력파일.mp4]  — Pleos Connect Emulator 2560x1440
cd "$(dirname "$0")/.." && python3 scripts/record_demo.py --find && python3 scripts/record_demo.py --record "${1:-giuncha_demo.mp4}"
