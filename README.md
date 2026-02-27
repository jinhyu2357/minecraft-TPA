# minecraft-TPA

Minecraft 서버에서 플레이어 간 순간이동 요청(TPA)을 간편하게 처리할 수 있는 플러그인입니다.

## 주요 기능
- `/tpa` 명령어로 다른 플레이어에게 순간이동 요청 전송
- `/tpaccept` 명령어로 요청 수락 후 순간이동 진행
- `/tpdeny` 명령어로 요청 거절
- `/tpareload` 명령어로 설정 파일(config) 즉시 리로드

## 명령어 안내

| 명령어 | 설명 | 권한(기본) |
| --- | --- | --- |
| `/tpa <player>` | 대상 플레이어에게 순간이동 요청을 보냅니다. | OP 또는 별도 플러그인 권한 설정 |
| `/tpaccept <player>` | 해당 플레이어의 순간이동 요청을 수락합니다. | OP 또는 별도 플러그인 권한 설정 |
| `/tpdeny <player>` | 해당 플레이어의 순간이동 요청을 거절합니다. | OP 또는 별도 플러그인 권한 설정 |
| `/tpareload` | `config` 파일을 다시 불러옵니다. | OP |

## 사용 예시
1. `Steve`가 `Alex`에게 요청 전송: `/tpa Alex`
2. `Alex`가 요청 수락: `/tpaccept Steve`
3. 요청을 거절하려면: `/tpdeny Steve`

## 참고
- 소스코드는 `master` 브랜치 기준으로 관리됩니다.
