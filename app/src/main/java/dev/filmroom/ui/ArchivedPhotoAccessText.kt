package dev.filmroom.ui

import android.content.Context
import dev.filmroom.i18n.L10n

/** Small UI-only table, following the export-history language mechanism. */
internal enum class ArchivedPhotoAccessText(private val zh:String,private val en:String,private val ja:String,private val ko:String) {
    TITLE("恢复照片访问", "Restore photo access", "写真へのアクセスを復元", "사진 접근 복원"),
    EXPLANATION("请授权照片访问，或手动选择对应的导出照片。", "Allow photo access, or manually select the matching exported photo.", "写真へのアクセスを許可するか、対応する出力写真を手動で選んでください。", "사진 접근을 허용하거나 해당 출력 사진을 직접 선택하세요."),
    GRANT("去授权", "Allow access", "アクセスを許可", "접근 허용"),
    CHOOSE("选择照片", "Choose photo", "写真を選択", "사진 선택"),
    PARTIAL("已允许访问部分照片。未选择的照片仍无法读取，可再次授权或手动选择对应导出照片。", "Access is limited to selected photos. Other photos remain unavailable; grant access again or choose the matching exported photo.", "選択した写真のみアクセスできます。他の写真は再度許可するか、対応する出力写真を選んでください。", "선택한 사진만 접근할 수 있습니다. 다른 사진은 다시 권한을 허용하거나 해당 출력 사진을 선택하세요."),
    DENIED("未获得照片读取权限。你仍可手动选择对应的导出照片进行关联。", "Photo access was not granted. You can still select the matching exported photo manually.", "写真の読み取りは許可されませんでした。対応する出力写真を手動で選択できます。", "사진 읽기 권한이 허용되지 않았습니다. 해당 출력 사진을 직접 선택할 수 있습니다."),
    INVALID_SELECTION("未能读取所选图片。请重新选择导出照片。", "Could not read that image. Please select the exported photo again.", "画像を読み取れませんでした。出力写真を選び直してください。", "이미지를 읽을 수 없습니다. 출력 사진을 다시 선택하세요."),
    LINK_TITLE("关联所选照片", "Link selected photo", "選択した写真を関連付ける", "선택한 사진 연결"),
    LINK_CONFIRM("确认将刚才选择的照片关联到这条备份记录？只有你确认后才会保存关联；文件名相同不代表它就是原成片。", "Link the photo you just selected to this backup record? The link is saved only after you confirm. A matching filename does not prove it is the original export.", "選択した写真をこの履歴に関連付けますか？確認後にのみ保存します。同じファイル名でも元の出力写真とは限りません。", "방금 선택한 사진을 이 백업 기록에 연결할까요? 확인한 후에만 저장됩니다. 파일 이름이 같아도 원래 출력 사진이라는 보장은 없습니다."),
    LINK("确认关联", "Confirm link", "関連付けを確認", "연결 확인"),
    PERSIST_FAILED("无法保留所选照片的读取权限，尚未关联。请通过系统文件选择器重新选择。", "Read access to the selected photo could not be retained. No link was saved. Select it again using the system file picker.", "写真の読み取り権限を保持できず、関連付けは保存されていません。システムのファイル選択から選び直してください。", "사진 읽기 권한을 유지할 수 없어 연결을 저장하지 않았습니다. 시스템 파일 선택기에서 다시 선택하세요."),
    LINK_FAILED("所选照片未通过记录校验，未修改关联。请确认选择的是这条记录对应的导出照片。", "The selected photo did not match this record. No link was changed. Select the exported photo belonging to this record.", "選択した写真が履歴の情報と一致せず、関連付けは変更していません。この履歴の出力写真を選んでください。", "선택한 사진이 기록 정보와 일치하지 않아 연결을 변경하지 않았습니다. 이 기록의 출력 사진을 선택하세요.");
    fun text(context:Context):String=when(L10n.language(context)){"en"->en;"ja"->ja;"ko"->ko;else->zh}
}
