// nMirror(com.aa.nmirror)가 외부에 열어둔 길안내 입력 창구.
//
// v: 재억 제보(2026-09-03, "우리 앱 카카오 안내는 차량 순정 HUD에 안 올라간다") - nMirror
// 4.8.0 APK를 뜯어 확인한 결과, nMirror는 순정 티맵 앱 내부에 갈고리를 걸어(Xposed)
// 안내 정보를 빼가고 있었고 우리 앱은 그 대상 목록에 없었다. 그런데 같은 정보를 받는
// 서비스 com.aa.nmirror.service.TbtService가 **android:exported="true"** 로 열려 있어서,
// 외부 앱이 그냥 붙어서 넣어줄 수 있다(루팅/Xposed/개발자 허락 전부 불필요).
//
// 여기 선언한 함수 하나가 nMirror 원본의 1번 창구(원본 이름 b(String,String))와 같은
// 자리다 - AIDL은 선언 순서대로 번호가 매겨지므로 이 함수가 반드시 첫 번째여야 한다.
// 넣은 값은 nMirror 안에서 티맵에서 빼온 정보와 똑같이 취급돼 차량 계기판·HUD·나브디·
// 오픈파일럿으로 그대로 흘러간다. #문제시 원복: 이 파일과 NMirrorSender만 지우면 됨
package com.aa.nmirror.service;

// v: 재억 요청(2026-09-04, "나브디에 경로선이 안 나온다") - nMirror 4.8.0을 다시 뜯어보니
// 이 창구에 함수가 하나가 아니라 **7개**였다. AIDL은 선언 순서로 번호가 매겨지므로,
// 3번째 자리인 경로 좌표를 쓰려면 1·2번도 자리를 채워 선언해야 한다. 원본 대조표:
//   1 b(String,String)   TBT 정보      → 우리가 쓰던 것
//   2 i(String)          신호등
//   3 f(List)            경로 좌표     ← 경로선을 그리는 데이터
//   4 h(int)             AI 상태
//   5 j(String)          AI POI 목록
//   6 g(byte[],long)     TBT 이미지
//   7 c(String)          (미확인)
// 경로 좌표를 넣으면 nMirror가 notiTmapRouteUpdated로 흘려 나브디에 20번 프레임
// (writeShort(20) + writeInt(개수*8) + float(위도),float(경도) 반복)으로 보낸다.
// 목록의 원소는 com.aa.nmirror.openpilot.tt.TTUtil$Coord(float 위도, float 경도)라
// 같은 이름·같은 필드 순서의 클래스를 우리 쪽에도 둬야 한다(TTUtil.java 참고). #문제시 원복
interface ITbtService {
    void sendTbt(String tbtInfoJson, String tbtListInfoJson);
    void sendTrafficSignal(String trafficSignalInfoJson);
    void sendRouteCoords(in List coords);
}
