package dev.filmroom.ui

import android.content.Context
import dev.filmroom.i18n.L10n

/** App-owned four-language copy, keyed independently of enum ordinals or preference wire values. */
internal enum class HistoryText(val zh:String,val en:String,val ja:String,val ko:String) {
 CONFIRM_RECORDS("将仅删除 %1\$d 条记录，保留导出的照片。","Delete %1\$d records only. Exported photos will be kept.","履歴 %1\$d 件のみ削除します。書き出した写真は残ります。","기록 %1\$d개만 삭제하고 내보낸 사진은 유지합니다。"),
 CONFIRM_PHOTOS("将删除 %1\$d 条记录及对应的导出照片，不会删除原图。","Delete %1\$d records and their exported photos. Originals will not be deleted.","履歴 %1\$d 件と対応する書き出し写真を削除します。元画像は削除しません。","기록 %1\$d개와 해당 내보낸 사진을 삭제합니다. 원본은 삭제하지 않습니다。"),
 SINGLE_TITLE("删除这条记录？","Delete this record?","この履歴を削除しますか？","이 기록을 삭제할까요?"),
 CLEAR_TITLE("清空已结束的记录？","Clear finished records?","終了した履歴を消去しますか？","종료된 기록을 비울까요?"),
 CLEAR_ONE_TITLE("清空这条已结束记录？","Clear this finished record?","この終了した履歴を消去しますか？","종료된 이 기록을 비울까요?"),
 PHOTO_ONE("是否同时删除这张导出的照片？","Also delete this exported photo?","この書き出した写真も削除しますか？","내보낸 이 사진도 삭제할까요?"),
 PHOTO_MANY("是否同时删除这些导出的照片？","Also delete these exported photos?","これらの書き出した写真も削除しますか？","내보낸 이 사진들도 삭제할까요?"),
 ONLY_CLEAR("否，仅清空记录","No, clear records only","いいえ、履歴のみ消去","아니요, 기록만 비우기"),
 CLEAR("清空","Clear","消去","비우기"),DELETE("删除","Delete","削除","삭제"),
 TITLE("同时删除导出的照片？","Also delete exported photos?","書き出した写真も削除しますか？","내보낸 사진도 삭제할까요?"),
 ONLY("否，仅删除记录","No, records only","いいえ、履歴のみ","아니요, 기록만 삭제"),
 BOTH("是，同时删除照片","Yes, also delete photos","はい、写真も削除","예, 사진도 삭제"),
 SINGLE_NOTE("仅处理这条记录对应的导出照片，不会删除原图。","Only this record’s exported photo. Originals are never deleted.","この履歴の書き出し写真のみが対象です。元画像は削除しません。","이 기록에 해당하는 내보낸 사진만 처리합니다. 원본은 삭제하지 않습니다."),
 CLEAR_NOTE("仅清理已结束的记录，排队、处理中和已暂停任务会保留。","Finished records only. Queued, running and paused tasks are kept.","終了した履歴のみ対象です。待機・処理・一時停止中のタスクは残ります。","종료된 기록만 처리합니다. 대기, 처리, 일시 중지된 작업은 유지됩니다."),
 RETRY_WARNING("失败记录移除后将无法重试。","Removed failed exports cannot be retried.","失敗した履歴を削除すると再試行できません。","실패한 기록을 삭제하면 다시 시도할 수 없습니다."),
 REMEMBER("记住删除范围","Remember deletion scope","削除範囲を保存","삭제 범위 기억"),
 HINT("仅记住删除范围，每次仍需确认。可在设置中修改。","Only the scope is remembered. Every deletion still requires confirmation. Change it in Settings.","削除範囲のみ保存します。削除のたびに確認します。設定で変更できます。","삭제 범위만 저장하며 삭제할 때마다 확인합니다. 설정에서 변경할 수 있습니다."),
 CANCEL("取消","Cancel","キャンセル","취소"),SAVE("保存","Save","保存","저장"),
 MISSING("未找到导出照片，可能已移动或删除。","Exported photo not found. It may have been moved or deleted.","書き出した写真が見つかりません。移動または削除された可能性があります。","내보낸 사진을 찾을 수 없습니다. 이동되거나 삭제되었을 수 있습니다."),
 IDENTITY("无法安全确认这张导出照片，记录已保留。可选择仅删除记录。","Cannot safely verify this exported photo. Record kept; you can remove only the record.","書き出した写真を安全に確認できません。履歴のみ削除できます。","내보낸 사진을 안전하게 확인할 수 없어 기록을 유지했습니다. 기록만 삭제할 수 있습니다."),
 FAILED("删除未完成，未处理的记录已保留。","Deletion incomplete. Unhandled records kept.","削除が完了しませんでした。未処理の履歴は残しました。","삭제가 완료되지 않아 처리하지 못한 기록을 유지했습니다."),
 DENIED("已取消删除，记录已保留。","Deletion cancelled. Record kept.","削除をキャンセルしました。履歴は残しました。","삭제를 취소하여 기록을 유지했습니다."),
 DONE("已移除 %1\$d 条记录。","Removed %1\$d records.","%1\$d 件の履歴を削除しました。","기록 %1\$d개를 삭제했습니다."),
 PROTECTED("任务状态已变化或正在处理，记录已保留。","Task changed or is still active. Record kept.","タスクが変更されたか処理中のため、履歴を残しました。","작업 상태가 변경되었거나 처리 중이므로 기록을 유지했습니다."),
 INTERRUPTED("删除已中断，剩余记录已保留，请重试。","Deletion interrupted. Remaining records kept; please retry.","削除が中断されました。残った履歴で再試行してください。","삭제가 중단되어 나머지 기록을 유지했습니다. 다시 시도해 주세요."),
 SETTINGS("导出记录清理","Export history cleanup","書き出し履歴の整理","내보내기 기록 정리"),
 SINGLE_SETTING("删除单条记录","Delete one record","履歴を1件削除","기록 한 개 삭제"),
 CLEAR_SETTING("清空导出列表","Clear export list","書き出し一覧を消去","내보내기 목록 비우기"),
 ASK("每次询问","Ask every time","毎回確認","매번 묻기"),
 RECORDS("仅记录","Records only","履歴のみ","기록만"),
 PHOTOS("记录和照片","Records and photos","履歴と写真","기록과 사진"),
 SETTING_NOTE("两个默认删除范围分别保存，每次删除仍会确认。系统授权另行询问。","Default scopes are saved separately. Every deletion still requires confirmation; system permission prompts also apply.","既定の削除範囲は別々に保存します。削除のたびに確認し、必要なシステム許可も求めます。","기본 삭제 범위는 각각 저장됩니다. 삭제할 때마다 확인하며 시스템 권한도 별도로 요청합니다."),
 SAVE_FAILED("无法保存选择，请重试。","Could not save choice. Please retry.","選択を保存できませんでした。再試行してください。","선택을 저장하지 못했습니다. 다시 시도해 주세요."),
 DELETE_A11Y("删除第 %1\$d 条导出记录","Delete export record %1\$d","書き出し履歴 %1\$d を削除","내보내기 기록 %1\$d 삭제"),
 DISABLED("任务尚未结束，不可删除","Task is not finished; deletion unavailable","未終了のため削除できません","종료되지 않은 작업은 삭제할 수 없습니다");
 fun text(context:Context,vararg args:Any):String {val value=when(L10n.language(context)){"en"->en;"ja"->ja;"ko"->ko;else->zh};return if(args.isEmpty())value else String.format(java.util.Locale.forLanguageTag(L10n.language(context)),value,*args)}
}
