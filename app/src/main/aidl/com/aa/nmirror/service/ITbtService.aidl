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

interface ITbtService {
    void sendTbt(String tbtInfoJson, String tbtListInfoJson);
}
