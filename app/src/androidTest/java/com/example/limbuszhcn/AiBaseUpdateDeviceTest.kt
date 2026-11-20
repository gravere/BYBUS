package com.example.limbuszhcn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.translation.*
import com.example.limbuszhcn.update.sha256
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class AiBaseUpdateDeviceTest {
    @Test fun selectiveBaseUpdatePrefersUpstreamEvenForEqualWordingKeepsOtherAiAndCanUndo() {
        val parent=InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath()
        val root=Files.createTempDirectory(parent,"base-update-test-")
        try {
            fun json(rows: Map<Int,String>)=JSONObject().put("dataList",JSONArray().apply {
                rows.forEach { (id,text)->put(JSONObject().put("id",id).put("content",text)) }
            }).toString()
            val folder=Files.createDirectories(root.resolve("Localize/jp"));val original=folder.resolve("JP_MainUIText.json")
            val raw=json(mapOf(1 to "最初の文",2 to "次の文",3 to "最後の文",4 to "続きの文"))
            Files.write(original,raw.toByteArray());val storage=ContainerWorkspaceStorage(folder.parent)
            fun archive(rows: Map<Int,String>)=Files.createTempFile(root,"pack",".zip").also { file->
                ZipOutputStream(Files.newOutputStream(file)).use { it.putNextEntry(ZipEntry("MainUIText.json"));it.write(json(rows).toByteArray());it.closeEntry() }
            }
            val sources=TranslationSources(root)
            val initial=archive(mapOf(1 to "旧底包",4 to "不变的底包"))
            val source=Files.newInputStream(initial).use { sources.import(it,"本地汉化.zip") }
            val base=sources.prepare(source);val tasks=AiTaskStore(root);val config=AiConfig()
            val task=tasks.createForSelection(config,sources,base,base,null,storage)
            var requests=0
            val client=AiTranslationClient(config,"fixture",AiHttpTransport { _,_,body->
                requests++;val input=JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
                JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content",
                    JSONObject().put("translations",JSONArray().apply { repeat(input.length()){put(JSONObject().put("id",input.getJSONObject(it).getString("id")).put("text","AI 译文"))} }).toString())))).toString()
            })
            val result=AiTranslationJob(root,storage,config,client).run(source.id,base,task.outputIdentity,session=tasks)
            publishAiResult(sources,tasks,tasks.read()!!,result);tasks.discard()
            val ai=sources.read().selected;assertEquals(2,ai.aiEntryCount)
            val updated=archive(mapOf(1 to "新的底包",2 to "AI 译文",4 to "不变的底包"))
            val latest=LocalTranslationArchive(root).extract(updated,"local-${updated.sha256()}")
            val changes=AiBaseUpdate(root);val report=changes.inspect(sources,ai,latest,storage)
            assertEquals(1,report.added);assertEquals(1,report.replaced);assertTrue(report.historical)
            val added=changes.page(report,AiBaseChangeKind.Added,0,android.os.CancellationSignal()).rows.single()
            assertEquals(added.current,added.updated)
            changes.apply(sources,storage,AiBaseRequest(report,AiBaseChangeKind.Added,count=1))
            val partial=sources.read().selected
            assertEquals(1,AiTranslationReuse.collect(root,partial,sources.prepare(partial),storage).size)
            val remaining=changes.inspect(sources,partial,latest,storage)
            assertEquals(1,remaining.total);assertEquals(1,remaining.replaced)
            changes.apply(sources,storage,AiBaseRequest(remaining,count=1))
            val finished=sources.read().selected
            val fields=TranslationInspection(storage,sources.prepare(finished)).read("jp/JP_MainUIText.json").fields
            assertEquals(listOf("新的底包","AI 译文","AI 译文","不变的底包"),fields.map { it.translated })
            assertEquals(0,changes.inspect(sources,finished,latest,storage).total)
            assertEquals(1,requests);assertEquals(raw,String(Files.readAllBytes(original)))
            sources.undoGenerated();assertEquals(partial.revision,sources.read().selected.revision)
            assertEquals(1,AiTranslationReuse.collect(root,sources.read().selected,sources.prepare(sources.read().selected),storage).size)
            Files.write(original,raw.replace("最初の文","変更した文").toByteArray())
            assertTrue(runCatching { changes.apply(sources,storage,AiBaseRequest(remaining,count=1)) }.isFailure)
        } finally { AtomicFiles.deleteOwnedTree(parent,root) }
    }
}
