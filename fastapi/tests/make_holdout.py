"""
지금 서빙 중인 가중치를 학습할 때 쓴 검증 분할(홀드아웃)을 그대로 되살린다.

왜 이게 필요한가.
  evaluate.py 는 "학습에 안 쓰인 사진"을 줘야 정직한 수치를 낸다. 그런데 학습 당시
  분할 목록을 파일로 남기지 않아서, HAM10000 전체로 재는 수밖에 없었다. 그 값은
  학습 이미지가 섞인 상한선이라 실제 실력보다 높게 나온다.

  다행히 학습 노트북(notebooks/pad_ham_training.ipynb)의 분할은 난수 시드가 고정된
  결정적 연산이다. 같은 입력에 같은 시드를 주면 같은 분할이 나온다. 그래서 이미지가
  아니라 **분할 절차**를 재현해서 목록을 복원한다.

  이 스크립트는 노트북 5번·6번 셀을 그대로 옮긴 것이다. 한 줄이라도 달라지면 다른
  분할이 나오므로, 노트북을 고치면 여기도 같이 고쳐야 한다.

무엇이 맞는지 어떻게 아는가 — 결론부터: 확정하지 못했다.
  --verify 를 주면 학습분 목록도 함께 쓴다. 복원이 맞다면 모델은 학습분에서 훨씬
  잘 맞히고 홀드아웃에서 눈에 띄게 떨어진다. 분할이 틀렸다면 두 쪽 다 비슷하게 나온다
  — 둘 다 학습·검증이 섞인 표본이 되기 때문이다.

  실제로 재보니 84.84% 대 83.86%, 격차가 표준오차(약 0.77%p) 안이라 어느 쪽인지
  가리지 못했다. 원래 학습 실행의 출력이 있으면 1초에 판정되는데 남아 있지 않다.
  그래도 결론이 흔들리지는 않는다 — 모델이 자기가 학습한 사진에서도 84.84% 밖에
  못 맞힌다는 건 외우지 않았다는 뜻이고, 그러면 오염이 있어도 점수를 1%p 이상
  부풀리지 못한다. 자세한 논증은 baselines/README.md.

이 스크립트를 다시 돌릴 일이 있는가 — 보통은 없다.
  결과물인 baselines/holdout.csv 는 이미 저장소에 고정돼 있고, 학습 노트북이 그
  목록을 읽어 학습에서 제외한다. 즉 다음 모델부터는 복원이 필요 없다 — 설계로
  보장된다. 이 스크립트는 데이터셋을 새로 구성해 목록 자체를 다시 정할 때만 쓴다.
  함부로 돌려서 목록이 바뀌면 baselines/ 의 기존 기록과 비교가 깨진다.

pandas / scikit-learn 이 필요하다. 추론 서버에는 없는 패키지라 컨테이너 밖에서 돌린다
(서빙에 쓰이는 코드가 아니라 데이터 준비 도구다).

  python3 tests/make_holdout.py \
      --ham-dir  ~/Downloads/CapstoneDesign/archive \
      --pad-dir  ~/Downloads/pad-work/pad-ufes-20-small \
      --scin-dir ~/Downloads/scin-work/scin-small \
      --out holdout.csv --verify
"""
import argparse
from pathlib import Path

import pandas as pd
from sklearn.model_selection import StratifiedGroupKFold

# ── 노트북 5번 셀과 완전히 같아야 한다 ──────────────────────────────
# 순서가 곧 모델 출력 인덱스의 의미다. 어긋나면 병명이 통째로 뒤바뀐다.
CLASSES = ['akiec', 'bcc', 'bkl', 'df', 'mel', 'nv', 'vasc', 'inflammatory']
CLS2IDX = {c: i for i, c in enumerate(CLASSES)}

PAD2HAM = {'BCC': 'bcc', 'ACK': 'akiec', 'SCC': 'akiec',
           'NEV': 'nv',  'SEK': 'bkl',   'MEL': 'mel'}
SCIN2CLS = {'eczema': 'inflammatory', 'acd': 'inflammatory',
            'urticaria': 'inflammatory', 'insect_bite': 'inflammatory'}


def build_frame(ham_dir: Path, pad_dir: Path, scin_dir: Path) -> pd.DataFrame:
    """노트북 5번 셀. concat 순서(ham → pad → scin)까지 같아야 한다 — 행 순서가 분할에 영향을 준다."""
    ham = pd.read_csv(ham_dir / 'HAM10000_metadata.csv')
    ham_df = pd.DataFrame({
        # 학습 때는 part_1/part_2 를 한 폴더로 합쳤다. 여기서는 파일명만 남긴다
        # (evaluate.py 가 --image-dir 를 여러 개 받아 알아서 찾는다).
        'image_id': ham['image_id'].astype(str) + '.jpg',
        'label': ham['dx'],
        'group': 'ham_' + ham['lesion_id'].astype(str),
        'src': 'ham',
    })

    pad = pd.read_csv(pad_dir / 'metadata.csv')
    pad_df = pd.DataFrame({
        'image_id': pad['img_id'],
        'label': pad['diagnostic'].map(PAD2HAM),
        'group': 'pad_' + pad['lesion_id'].astype(str),
        'src': 'pad',
    })

    scin = pd.read_csv(scin_dir / 'metadata.csv')
    scin_df = pd.DataFrame({
        'image_id': scin['img_id'],
        'label': scin['diagnostic'].map(SCIN2CLS),
        'group': 'scin_' + scin['case_id'].astype(str),
        'src': 'scin',
    })

    df = pd.concat([ham_df, pad_df, scin_df], ignore_index=True)
    df['label_idx'] = df['label'].map(CLS2IDX)
    missing = df['label_idx'].isna().sum()
    if missing:
        raise SystemExit(f'매핑 안 된 라벨 {missing}건 — 메타데이터가 학습 당시와 다릅니다')
    return df


def main() -> None:
    ap = argparse.ArgumentParser(description='학습 당시 검증 분할을 복원해 holdout.csv 를 만든다')
    ap.add_argument('--ham-dir', type=Path, required=True, help='HAM10000_metadata.csv 가 있는 폴더')
    ap.add_argument('--pad-dir', type=Path, required=True, help='PAD-UFES-20 metadata.csv 가 있는 폴더')
    ap.add_argument('--scin-dir', type=Path, required=True, help='SCIN metadata.csv 가 있는 폴더')
    ap.add_argument('--out', type=Path, default=Path('holdout.csv'))
    ap.add_argument('--seed', type=int, default=42, help='노트북 6번 셀의 SEED')
    ap.add_argument('--n-splits', type=int, default=5, help='노트북 6번 셀의 n_splits')
    ap.add_argument('--verify', action='store_true',
                    help='학습분 목록도 함께 쓴다(복원이 맞는지 대조용)')
    args = ap.parse_args()

    df = build_frame(args.ham_dir, args.pad_dir, args.scin_dir)

    # ── 노트북 6번 셀 ──
    sgkf = StratifiedGroupKFold(n_splits=args.n_splits, shuffle=True, random_state=args.seed)
    tr_idx, va_idx = next(sgkf.split(df, df['label_idx'], groups=df['group']))
    train_df, valid_df = df.iloc[tr_idx], df.iloc[va_idx]

    overlap = set(train_df['group']) & set(valid_df['group'])
    if overlap:
        raise SystemExit(f'데이터 누수 — 같은 병변이 양쪽에 있습니다 ({len(overlap)}개 그룹)')

    cols = ['image_id', 'label', 'src']
    valid_df[cols].to_csv(args.out, index=False)
    print(f'전체 {len(df)}장 / {df["group"].nunique()}병변')
    print(f'홀드아웃 {len(valid_df)}장 → {args.out}')
    print()
    print('홀드아웃 구성 (행=병명, 열=출처)')
    print(valid_df.groupby(['label', 'src']).size().unstack(fill_value=0).to_string())

    if args.verify:
        train_out = args.out.with_name(args.out.stem + '-train' + args.out.suffix)
        train_df[cols].to_csv(train_out, index=False)
        print()
        print(f'학습분 {len(train_df)}장 → {train_out}')
        print('  이 둘을 각각 evaluate.py 로 재서 학습분이 확실히 더 높으면 복원이 맞은 것이다.')
        print('  두 값이 비슷하면 분할이 재현되지 않은 것이므로 이 홀드아웃을 믿으면 안 된다.')


if __name__ == '__main__':
    main()
