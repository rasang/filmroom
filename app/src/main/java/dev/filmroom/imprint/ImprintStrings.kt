package dev.filmroom.imprint

import android.content.Context
import dev.filmroom.i18n.L10n

internal enum class ImprintText(val zh:String,val en:String,val ja:String,val ko:String) {
    TITLE("日期和参数","Date & data","日付と撮影データ","날짜 및 촬영 정보"),
    DATE("日期","Date","日付","날짜"),
    DATE_DIALOG("日期印字","Date imprint","日付の印字","날짜 인쇄"),
    DATE_TIME("日期和时间","Date and time","日付と時刻","날짜 및 시간"),
    TIME("时间","Time","時刻","시간"),
    CHOOSE_DATE("选择日期","Choose date","日付を選択","날짜 선택"),
    CHOOSE_TIME("选择时间","Choose time","時刻を選択","시간 선택"),
    SELECT("确定","Set","決定","설정"),
    TIME_PRECISION_NOTE("时间选择器修改小时和分钟；已有秒数保持不变，首次选择时间时秒数为00。","The time picker changes hours and minutes. Existing seconds are kept; the first manual time uses 00 seconds.","時刻の選択で変更するのは時・分です。既存の秒は保持し、初めて指定する場合は00秒になります。","시간 선택기는 시와 분을 변경합니다. 기존 초는 유지하며 처음 직접 선택할 때는 00초를 사용합니다."),
    MANUAL("手动日期","Manual date","手動の日付","수동 날짜"),
    ORIGINAL("原图拍摄时间","Original capture time","元画像の撮影日時","원본 촬영 시간"),
    RESTORE_CAPTURE("恢复原图拍摄时间","Restore original capture time","元の撮影日時に戻す","원본 촬영 시간 복원"),
    INVALID_DATE_TIME("请选择日期和时间后再应用。","Select both a date and a time before applying.","日付と時刻を両方選択してから適用してください。","날짜와 시간을 모두 선택한 후 적용하세요."),
    DATE_NOTE("默认读取原图拍摄时间。手动修改只用于印字，不更改原图信息。没有拍摄时间时留空，不使用当前时间、编辑时间或文件时间。","Defaults to the original capture time. Manual edits affect the imprint only, not source metadata. Missing capture times stay empty; current, edit and file times are never substituted.","初期値は元画像の撮影日時です。手動の変更は印字のみに適用され、元画像の情報は変わりません。撮影日時がない場合は空欄とし、現在・編集・ファイルの日時で代用しません。","기본값은 원본 촬영 시간입니다. 수동 변경은 인쇄에만 적용되며 원본 정보는 바뀌지 않습니다. 촬영 시간이 없으면 비워 두며 현재·편집·파일 시간으로 대체하지 않습니다."),
    SHARED_APPEARANCE("字号和光晕也影响片边参数","Size and glow also affect edge data","文字サイズとにじみは端の撮影情報にも適用","글자 크기와 빛 번짐은 가장자리 정보에도 적용"),
    STYLE_SAMPLE("字形示例 · 不用于印字","Lettering sample · not printed","字体サンプル・印字には使いません","글꼴 예시 · 인쇄되지 않음"),
    DATE_STYLE("日期字形","Date lettering","日付の字体","날짜 글꼴"),
    OFF("关闭","Off","オフ","끄기"),
    SEGMENT("七段 · CONTAX T2","Seven segment · CONTAX T2","7セグメント · CONTAX T2","7세그먼트 · CONTAX T2"),
    DOT("点阵 · Yashica T4 Zoom","Dot matrix · Yashica T4 Zoom","ドット · Yashica T4 Zoom","도트 · Yashica T4 Zoom"),
    STROKE("连续笔画 · Canon Data Back A","Continuous · Canon Data Back A","連続線 · Canon Data Back A","연속 획 · Canon Data Back A"),
    KANJI("年月日 · Canon LUNA XL","年/月/日 · Canon LUNA XL","年月日 · Canon LUNA XL","年/月/日 · Canon LUNA XL"),
    CAPTURE("日期默认读取原图；参数只读取原图拍摄元信息","Date defaults to the original; capture data uses original metadata only","日付の初期値と撮影情報は元画像の記録を使用","날짜 기본값과 촬영 정보는 원본 메타데이터를 사용합니다"),
    PLACEMENT("日期区域","Date area","日付の領域","날짜 영역"),
    IN_PHOTO("照片内","Inside photo","写真内","사진 안"),
    ON_EDGE("120片边","120 film edge","120フィルム端","120 필름 가장자리"),
    POSITION("日期位置 · 原片方向","Date position · native orientation","日付の位置・元の向き","날짜 위치 · 원본 방향"),
    BR("右下","Bottom right","右下","오른쪽 아래"),BL("左下","Bottom left","左下","왼쪽 아래"),
    TR("右上","Top right","右上","오른쪽 위"),TL("左上","Top left","左上","왼쪽 위"),
    SIZE("字号","Size","文字サイズ","글자 크기"),GLOW("光晕","Glow","光のにじみ","빛 번짐"),
    EXPOSURE("片边拍摄参数","Capture data on film edge","フィルム端の撮影情報","필름 가장자리 촬영 정보"),
    NOTE("日期位置按原片方向，随边框旋转；无边框时按照片方向。日期默认使用原图拍摄时间，可手动修改印字日期。参数仅使用原图记录的ISO、测光、快门、光圈、曝光补偿与实际焦距。缺失项不印；不采用编辑日期或35mm等效焦距。","Positions follow the native film orientation and rotate with the frame; without a frame they follow the photo. Dates default to the original capture time and can be manually edited for the imprint. Data uses only original ISO, metering, shutter, aperture, compensation and actual focal length. Missing fields are omitted; edit dates and 35mm-equivalent focal length are never substituted.","位置は元のフィルム方向に従い、フレームと回転します。フレームなしでは写真方向です。日付の初期値は元の撮影日時で、印字用に手動変更できます。撮影情報は元画像のISO・測光・シャッター・絞り・露出補正・実焦点距離のみを使用。不明な項目は省略し、編集日や35mm換算値に置き換えません。","위치는 원래 필름 방향을 따르고 프레임과 회전합니다. 프레임이 없으면 사진 방향을 따릅니다. 날짜는 원본 촬영 시간을 기본값으로 하며 인쇄용으로 수동 수정할 수 있습니다. 촬영 정보는 원본의 ISO, 측광, 셔터, 조리개, 노출 보정, 실제 초점거리만 사용합니다. 누락 항목은 생략하고 편집일이나35mm 환산값으로 대체하지 않습니다."),
    EDGE_NOTE("135参数位于齿孔间；120参数位于品牌文字对侧，仅移动已有标记，不补造箭头或数字。ISO和焦段是本次设计扩展。长数值会等比缩小，不截断；尺寸过小无法完整排入时不印。","135 data sits between perforations; 120 data sits opposite the brand. Only existing marks move. ISO and focal length are design additions. Long values scale uniformly, never truncate; values that cannot fit at the smallest size are omitted.","135は穴の間、120はブランド文字の反対側に配置。既存の印だけ移動。ISOと焦点距離は今回のデザイン拡張です。長い値は比率を保って縮小し、収まらない場合は省略します。","135는 천공 사이,120은 브랜드 반대편에 배치합니다. 기존 표식만 이동합니다. ISO와 초점거리는 디자인 확장입니다. 긴 값은 비율을 유지해 줄이며 잘라내지 않고, 들어가지 않으면 생략합니다."),
    EDGE_DATE_INACTIVE("片边日期仅在120边框显示；当前不会打印此日期。","Edge dates require a 120 frame and are currently hidden.","フィルム端の日付は120フレームのみで表示されます。","가장자리 날짜는120 프레임에서만 표시됩니다."),
    MISSING("没有可用的拍摄日期","No capture date available","撮影日がありません","촬영 날짜 없음"),
    NO_EXPOSURE("没有可用的曝光数据","No exposure data available","露出データがありません","노출 정보 없음"),
    INACTIVE("当前相框不支持片边曝光数据","This frame does not support edge data","このフレームでは端のデータを表示できません","현재 프레임은 가장자리 정보를 지원하지 않습니다"),
    APPLY("应用","Apply","適用","적용"),CANCEL("取消","Cancel","キャンセル","취소"),
    PREVIEW("印字内容","Imprint content","印字内容","표시할 내용");
    fun text(context:Context)=when(L10n.language(context)){"en"->en;"ja"->ja;"ko"->ko;else->zh}
}
