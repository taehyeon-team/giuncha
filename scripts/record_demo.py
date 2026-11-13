# -*- coding: utf-8 -*-
"""기운차 시연 영상 자동 녹화 (v0.3: 캐릭터 모드 기준, 약 93초).

누를 곳을 좌표가 아니라 화면 글자로 찾기 때문에 화면 배치가 바뀌어도 그대로 쓸 수 있다.
1) 먼저 한 번 돌며 누를 곳의 좌표를 찾아 적어 두고(--find), 2) 그 좌표로 다시 돌며 녹화한다(--record).
글자로 찾는 데 시간이 걸려서, 녹화할 때는 미리 찾아 둔 좌표만 쓴다.

사용:  python3 scripts/record_demo.py --find            # 좌표 찾기 (taps.json 저장)
       python3 scripts/record_demo.py --record out.mp4   # 녹화
환경:  Pleos Connect Emulator 2.1.0 (2560x1440), adb 가 PATH 에 있거나 ADB 환경변수로 지정
"""
import json, os, re, subprocess, sys, time

ADB = os.environ.get("ADB") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "ai.taehyeon.giuncha"
HERE = os.path.dirname(os.path.abspath(__file__))
TAPS = os.path.join(HERE, "record_demo_taps.json")

# (누를 글자, 누른 뒤 보여 줄 시간(초)). 글자가 "-" 이면 누르지 않고 기다리기만 한다.
PLAN = [
    ("-", 1.5),                        # 처음 설정
    ("아침 탑승", 0.3),                    # (시연 줄은 투명) 아침으로 맞춤
    ("나를 알아보기", 4.3),             # ① 정체성 카드 + 내 띠 친구 이름 짓기
    ("시작하기", 4.4),                 # ② 오늘의 카드
    ("목적지(두물머리)", 3.8),          # ③ 오늘의 데스티니
    ("좋아요", 1.0),
    ("출발 D", 3.2),                    # ④ 출발: 멈춘 그림 + 한 줄
    ("보호구역", 3.6),                  # ④ 주의 구간: 조심 표정
    ("퇴근 도착", 4.0),                    # ⑤ 도착: 오늘 지나온 길 + 복
    ("좋아요", 2.8),                    # ⑥ 한 단어 묻기
    ("생각이 많아요", 4.0),             # ⑥ 답글 + 내일 아침 예고
    ("조금 더 이야기", 3.0),            # ⑦ 조금 더 이야기
    ("일", 4.2),
    ("내일 아침에 한마디", 1.8),
    ("다음 날", 4.6),                   # ⑧ 다음 날: 어제 마음 반영 + 이어 가는 한 줄
    ("동승", 0.6),                     # 동승: 같이
    ("퇴근 도착", 2.8),                    # ⑩ 같이 탄 날
    ("옆자리 친구 만나기", 2.6),
    ("토끼", 3.8),
    ("동승", 0.5), ("동승", 0.6),     # 동승: 자동 → 혼자
    ("7일 뒤", 2.2),                    # 기록 관문
    ("혼자예요, 열기", 5.0),            # ⑨ 7일 쌓임 + 자란 친구
    ("복 +50", 0.3), ("복 +50", 0.3), ("복 +50", 0.3),
    ("내 친구", 3.2),                   # ⑪ 내 친구
    ("해태", 3.0),                      # 수호 친구 만나기
    ("아침 탑승", 3.0),                    # 수호 친구와 함께하는 오늘의 카드
    ("화면 모드", 2.6),                 # 화면 모드 세 가지
    ("키치 모드", 2.6),
    ("화면 모드", 0.8),
    ("차분 모드", 2.6),
]


def sh(*a, raw=False, timeout=60):
    r = subprocess.run([ADB, *a], capture_output=True, timeout=timeout)
    return r.stdout if raw else r.stdout.decode("utf-8", "replace")


def fresh(rec):
    """앱을 처음 상태로 켠다. rec=True 면 녹화 모드(시연 줄이 투명, 장면 자막 표시)."""
    sh("shell", "am", "force-stop", "--user", "10", PKG)
    sh("shell", "pm", "clear", "--user", "10", PKG)
    sh("shell", "pm", "grant", "--user", "10", PKG, "pleos.car.permission.CAR_SEATS")
    sh("shell", "am", "start", "--user", "10", "-n", PKG + "/.MainActivity", "--ez", "rec", "true" if rec else "false")
    time.sleep(8)


def find(txt):
    x = sh("exec-out", "uiautomator", "dump", "/dev/tty")
    nodes = re.findall(r'<node[^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
    c = [n for n in nodes if txt in n[0] or txt in n[1]]
    if not c:
        return None
    n = min(c, key=lambda n: len(n[0] or n[1]))
    return (int(n[2]) + int(n[4])) // 2, (int(n[3]) + int(n[5])) // 2


def do_find():
    # 녹화 모드에서는 시연 줄이 투명해서 글자로 찾을 수 없다. 좌표는 같으므로 보통 모드에서 찾는다.
    fresh(False); out = []
    for txt, wait in PLAN:
        if txt == "-":
            out.append(None); time.sleep(1); continue
        xy = find(txt)
        if not xy:
            print("못 찾음:", txt); sys.exit(1)
        out.append(xy); sh("shell", "input", "tap", str(xy[0]), str(xy[1])); time.sleep(2.0)
        print(txt, xy)
    json.dump(out, open(TAPS, "w")); print("좌표 저장:", TAPS)


def do_record(dst):
    taps = json.load(open(TAPS)); assert len(taps) == len(PLAN), "먼저 --find 를 다시 돌리세요"
    fresh(True)
    sh("shell", "rm", "-f", "/sdcard/giuncha_demo.mp4")
    rec = subprocess.Popen([ADB, "shell", "screenrecord", "--size", "1920x1080", "--bit-rate", "10000000", "--time-limit", "170", "/sdcard/giuncha_demo.mp4"])
    time.sleep(2.5); t0 = time.time()
    for (txt, wait), xy in zip(PLAN, taps):
        if xy:
            sh("shell", "input", "tap", str(xy[0]), str(xy[1]))
        time.sleep(wait)
    print("녹화 길이 약 %.0f초" % (time.time() - t0 + 2.5))
    sh("shell", "pkill", "-INT", "screenrecord"); time.sleep(4); rec.wait(timeout=20)
    subprocess.run([ADB, "pull", "/sdcard/giuncha_demo.mp4", dst], check=True); print("저장:", dst)


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "--find":
        do_find()
    elif len(sys.argv) >= 3 and sys.argv[1] == "--record":
        do_record(sys.argv[2])
    else:
        print(__doc__)
