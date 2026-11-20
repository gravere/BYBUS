package com.example.limbuszhcn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.limbuszhcn.translation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.limbuszhcn.ui.theme.LauncherColors as C

@Composable
internal fun AiBaseUpdatePane(state: LauncherUiState, actions: TranslationActions) {
    val update=state.ai.baseUpdate;val report=update.report
    var kind by remember(report?.key){mutableStateOf<AiBaseChangeKind?>(null)}
    var page by remember(report?.key,kind){mutableIntStateOf(0)}
    var result by remember(report?.key){mutableStateOf<AiBasePage?>(null)}
    var loaded by remember(report?.key){mutableStateOf<Pair<AiBaseChangeKind?,Int>?>(null)}
    var reading by remember { mutableStateOf(false) }
    var readError by remember(report?.key){mutableStateOf<String?>(null)}
    var expanded by remember(report?.key){mutableStateOf<String?>(null)}
    val selected=remember(report?.key){mutableStateMapOf<String,Boolean>()}
    var confirmation by remember { mutableStateOf<AiBaseRequest?>(null) }
    val enabled=!state.busy && !state.ai.hasPendingTask && !update.loading && !reading && loaded == (kind to page)
    LaunchedEffect(report?.key,kind,page){
        if(report==null)return@LaunchedEffect
        reading=true;readError=null
        val signal=android.os.CancellationSignal()
        try{result=withContext(Dispatchers.IO){actions.baseChanges(report,kind,page,signal)};loaded=kind to page}
        catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(_:Exception){result=null;readError="明细读取失败，请重新检查"}
        finally{signal.cancel();reading=false}
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        DetailCard{
            Row(verticalAlignment=Alignment.CenterVertically){
                Text("同步原包译文",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                ActionIconButton(Glyph.Refresh,"检查原包更新",{update.sourceId?.let(actions.inspectBaseUpdate)},enabled=!state.busy,busy=update.loading)
            }
            if(update.loading) OperationProgress(state)
            report?.let{
                Text("新增 ${it.added} 条 · 改写 ${it.replaced} 条",style=MaterialTheme.typography.bodyMedium)
                if(!it.historical)Text("无法读取生成时的原包，下面对比最新原包与当前译文。",style=MaterialTheme.typography.bodySmall,color=C.Muted)
                if(it.total==0)Text("没有需要替换的译文",style=MaterialTheme.typography.bodyMedium)
            }
            update.error?.let{Text(it,color=C.Error,style=MaterialTheme.typography.bodyMedium)}
            update.message?.let{Text(it,style=MaterialTheme.typography.bodyMedium)}
        }
        if(report!=null){
            DetailCard{
                SourceTabs(listOf(null,AiBaseChangeKind.Added,AiBaseChangeKind.Replaced),kind,{it?.label?:"全部"},"原包变动",{kind=it})
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text(if(selected.isEmpty())"${result?.total?:0} 条" else "已选 ${selected.size} 条",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                    if(selected.isNotEmpty())TextButton({selected.clear()}){Text("清空选择")}
                }
                if(reading)LinearProgressIndicator(Modifier.fillMaxWidth())
                readError?.let{Text(it,color=C.Error)}
                result?.takeIf { loaded == (kind to page) }?.rows?.forEach { row ->
                    HorizontalDivider(color=C.Line)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f).clickable{expanded=if(expanded==row.id)null else row.id}.padding(vertical=8.dp)){
                            Text(if(row.current==row.updated) "文字相同，采用原包译文" else row.kind.label,color=C.Accent,style=MaterialTheme.typography.labelSmall)
                            Text(row.updated,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                        Checkbox(row.id in selected,{if(it&&selected.size<1000)selected[row.id]=true else selected.remove(row.id)},enabled=enabled)
                    }
                    if(expanded==row.id){
                        Text("当前译文",style=MaterialTheme.typography.labelSmall,color=C.Muted)
                        Text(row.current?:"尚无译文",style=MaterialTheme.typography.bodyMedium)
                        Text("原包新译文",style=MaterialTheme.typography.labelSmall,color=C.Muted)
                        Text(row.updated,style=MaterialTheme.typography.bodyMedium)
                    }
                }
                result?.takeIf{it.pages>1}?.let{p->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                    TextButton({page--},enabled=enabled&&page>0){Text("上一页")}
                    Text("${p.page+1}/${p.pages}",style=MaterialTheme.typography.labelMedium)
                    TextButton({page++},enabled=enabled&&page+1<p.pages){Text("下一页")}
                }}
            }
            Button({confirmation=AiBaseRequest(report,kind,selected.keys.toSet().takeIf{it.isNotEmpty()},
                if(selected.isNotEmpty())selected.size else result?.total?:0)},enabled=enabled&&readError==null&&result!=null&&
                    (report.total==0||selected.isNotEmpty()||(result?.total?:0)>0),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){
                Text(if(report.total==0)"同步原包版本" else if(selected.isEmpty())"一键更新当前筛选" else "更新所选 ${selected.size} 条")
            }
        }
    }
    confirmation?.let{request->BybusDialog(onDismissRequest={confirmation=null},title={Text("更新 AI 汉化包？")},
        text={Text("将关闭游戏，用原包译文替换所选 ${request.count} 条。其他 AI 译文保留，不调用 AI、不产生 API 费用。可撤回这次改动。")},
        confirmButton={TextButton({confirmation=null;actions.applyBaseUpdate(request)},enabled=enabled){Text("更新")}},
        dismissButton={TextButton({confirmation=null}){Text("取消")}})}
}
