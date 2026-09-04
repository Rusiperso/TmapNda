package com.aa.nmirror.openpilot.tt;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * nMirror가 경로 좌표 목록의 원소로 쓰는 클래스를 그대로 흉내 낸 것.
 *
 * v: 재억 요청(2026-09-04) - nMirror에 경로 좌표를 넘기려면 목록 원소가
 * `com.aa.nmirror.openpilot.tt.TTUtil$Coord` 여야 한다. 안드로이드가 목록을 포장할 때
 * **클래스 이름을 글자로 적어 보내고**, 받는 쪽(nMirror)이 그 이름으로 자기 클래스를 찾아
 * 풀기 때문에, 우리 앱에 같은 이름·같은 필드 순서의 클래스를 두면 그대로 전달된다.
 * nMirror 원본을 디컴파일해 확인한 내용(4.8.0):
 *   - 필드 두 개가 float이고 순서는 (위도, 경도) - 원본이 티맵 엔진의 getLatitude(),
 *     getLongitude()를 그 순서로 넣는다.
 *   - writeToParcel도 위도 → 경도 순서.
 * 이 파일은 nMirror에 넘길 때만 쓴다. #문제시 원복: 이 파일과 NMirrorSender의
 * sendRouteCoordsIfChanged()만 지우면 된다.
 */
public class TTUtil {

    public static class Coord implements Parcelable {

        public float latitude;
        public float longitude;

        public Coord(float latitude, float longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        protected Coord(Parcel parcel) {
            latitude = parcel.readFloat();
            longitude = parcel.readFloat();
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeFloat(latitude);
            parcel.writeFloat(longitude);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<Coord> CREATOR = new Creator<Coord>() {
            @Override
            public Coord createFromParcel(Parcel parcel) {
                return new Coord(parcel);
            }

            @Override
            public Coord[] newArray(int size) {
                return new Coord[size];
            }
        };
    }
}
