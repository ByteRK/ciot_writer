package com.ciot.writer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private TemplateStore store;
    private List<ProductTemplate> templates;
    private Spinner templateSpinner;
    private EditText serverUrl, host;
    private CheckBox autoReboot;
    private TextView logView;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final int IMPORT_JSON = 3101, EXPORT_JSON = 3102;
    private boolean updatingSpinner = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new TemplateStore(this); templates = store.load();
        setContentView(R.layout.activity_main);

        serverUrl = findViewById(R.id.serverUrl);
        host = findViewById(R.id.host);
        autoReboot = findViewById(R.id.autoReboot);
        logView = findViewById(R.id.logView);
        templateSpinner = findViewById(R.id.templateSpinner);

        Button saveServer = findViewById(R.id.saveServer);
        Button testServer = findViewById(R.id.testServer);
        Button addTemplate = findViewById(R.id.addTemplate);
        Button editTemplate = findViewById(R.id.editTemplate);
        Button deleteTemplate = findViewById(R.id.deleteTemplate);
        Button importTemplates = findViewById(R.id.importTemplates);
        Button exportTemplates = findViewById(R.id.exportTemplates);
        Button checkConnection = findViewById(R.id.checkConnection);
        Button burn = findViewById(R.id.burn);
        Button syncRestart = findViewById(R.id.syncRestart);
        Button copyLog = findViewById(R.id.copyLog);

        serverUrl.setText(store.getServerUrl());
        host.setText(store.getHost());
        autoReboot.setChecked(store.getAutoReboot());

        saveServer.setOnClickListener(v -> { store.setServerUrl(serverUrl.getText().toString()); toast("服务器地址已保存"); });
        testServer.setOnClickListener(v -> checkServer(testServer));
        addTemplate.setOnClickListener(v -> editTemplate(null));
        editTemplate.setOnClickListener(v -> editTemplate(currentTemplate()));
        deleteTemplate.setOnClickListener(v -> deleteTemplate());
        importTemplates.setOnClickListener(v -> importTemplates());
        exportTemplates.setOnClickListener(v -> exportTemplates());
        templateSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { if (!updatingSpinner) store.setSelected(position); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        checkConnection.setOnClickListener(v -> runCheck());
        burn.setOnClickListener(v -> burn());
        syncRestart.setOnClickListener(v -> syncRestart());
        copyLog.setOnClickListener(v -> copyLog());

        refreshSpinner(store.getSelected());
    }

    private void copyLog() {
        String text = logView == null ? "" : logView.getText().toString();
        if (text.isEmpty() || text.equals(getString(R.string.log_waiting))) { toast("暂无日志可复制"); return; }
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("CIOT Writer 日志", text));
        toast("日志已复制到剪贴板");
    }

    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_SHORT).show(); }
    private ProductTemplate currentTemplate() { int i=templateSpinner==null?0:templateSpinner.getSelectedItemPosition(); return templates.get(Math.max(0,Math.min(i,templates.size()-1))); }

    private void refreshSpinner(int selected) {
        if(templateSpinner==null)return;
        List<String> names=new ArrayList<>(); for(ProductTemplate t:templates) names.add(t.name+"  ·  PID "+(t.pid.isEmpty()?"未设置":t.pid));
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,names); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        updatingSpinner = true;
        templateSpinner.setAdapter(adapter);
        int index=Math.max(0,Math.min(selected,templates.size()-1)); templateSpinner.setSelection(index);
        updatingSpinner = false;
        store.setSelected(index);
    }

    private void editTemplate(ProductTemplate original) {
        final boolean editing=original!=null; final ProductTemplate t=editing?original:new ProductTemplate();
        View form=getLayoutInflater().inflate(R.layout.dialog_template,null);
        EditText name=form.findViewById(R.id.fieldName), pid=form.findViewById(R.id.fieldPid), nic=form.findViewById(R.id.fieldNic);
        EditText path=form.findViewById(R.id.fieldPath), port=form.findViewById(R.id.fieldPort);
        EditText username=form.findViewById(R.id.fieldUsername), password=form.findViewById(R.id.fieldPassword);
        EditText content=form.findViewById(R.id.fieldContent);
        name.setText(t.name); pid.setText(t.pid); nic.setText(t.networkInterface); path.setText(t.targetPath);
        port.setText(String.valueOf(t.port)); username.setText(t.username); password.setText(t.password); content.setText(t.contentTemplate);
        new AlertDialog.Builder(this).setTitle(editing?"编辑产品模板":"新增产品模板").setView(form)
            .setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
                String p=pid.getText().toString().trim(), n=name.getText().toString().trim(), iface=nic.getText().toString().trim(), target=path.getText().toString().trim(), body=content.getText().toString();
                int telnetPort; try{telnetPort=Integer.parseInt(port.getText().toString());}catch(Exception ex){toast("Telnet 端口无效");return;}
                if(n.isEmpty()||p.isEmpty()||!iface.matches("[A-Za-z0-9_.:-]{1,64}")||!target.startsWith("/")||telnetPort<1||telnetPort>65535){toast("请检查名称、PID、网卡、绝对路径和端口");return;}
                for(String slot:new String[]{"{{pid}}","{{did}}","{{license}}"})if(!body.contains(slot)){toast("文件模板缺少 "+slot);return;}
                t.name=n;t.pid=p;t.networkInterface=iface;t.targetPath=target;t.port=telnetPort;t.username=username.getText().toString();t.password=password.getText().toString();t.contentTemplate=body;
                if(!editing)templates.add(t); store.save(templates);refreshSpinner(templates.indexOf(t));toast("模板已保存");
            }).show();
    }
    private void deleteTemplate() {
        if(templates.size()<2){toast("至少保留一个模板");return;}
        int index=templateSpinner.getSelectedItemPosition(); new AlertDialog.Builder(this).setTitle("删除模板").setMessage("删除“"+currentTemplate().name+"”？")
            .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{templates.remove(index);store.save(templates);refreshSpinner(Math.max(0,index-1));}).show();
    }
    private void importTemplates() { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/json");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,IMPORT_JSON); }
    private void exportTemplates() { Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/json");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,"ciot-writer-templates.json");startActivityForResult(i,EXPORT_JSON); }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data); if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        try {
            if(requestCode==IMPORT_JSON){try(InputStream in=getContentResolver().openInputStream(data.getData())){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);List<ProductTemplate> imported=TemplateStore.parseImport(new String(out.toByteArray(),StandardCharsets.UTF_8));new AlertDialog.Builder(this).setTitle("替换产品模板").setMessage("导入文件包含 "+imported.size()+" 个模板，将替换当前全部模板。是否继续？").setNegativeButton("取消",null).setPositiveButton("导入",(dialog,which)->{templates=imported;store.save(templates);refreshSpinner(0);toast("模板导入完成："+templates.size()+" 个");}).show();}}
            else if(requestCode==EXPORT_JSON){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){out.write(TemplateStore.exportJson(templates).getBytes(StandardCharsets.UTF_8));toast("模板已导出");}}
        }catch(Exception e){toast("文件处理失败："+e.getMessage());}
    }

    private void runCheck() {
        final ProductTemplate t=currentTemplate(); final String device=host.getText().toString().trim(); if(device.isEmpty()){toast("请输入设备 IP");return;}
        append("正在连接 "+device+":"+t.port);
        worker.execute(()->{try{WriterService.checkConnection(device,t);runOnUiThread(()->append("Telnet 登录成功"));}catch(Exception e){runOnUiThread(()->append("连接失败："+e.getMessage()));}});
    }
    private void checkServer(Button button) {
        final String api=serverUrl.getText().toString().trim(); if(api.isEmpty()){toast("请输入授权服务器地址");return;}
        store.setServerUrl(api); button.setEnabled(false); append("正在检测授权服务器接口："+api);
        worker.execute(()->{try{WriterService.checkServer(api);runOnUiThread(()->{append("授权服务器连接正常");toast("服务器连接成功");button.setEnabled(true);});}
            catch(Exception e){runOnUiThread(()->{append("服务器检测失败："+e.getMessage());toast("服务器连接失败");button.setEnabled(true);});}});
    }
    private void burn() {
        final ProductTemplate t=currentTemplate(); final String device=host.getText().toString().trim(), api=serverUrl.getText().toString().trim();
        if(device.isEmpty()||api.isEmpty()){toast("请填写设备 IP 和授权服务器地址");return;}
        if(!t.pid.trim().matches(".+")){toast("请先配置模板 PID");return;}
        store.setServerUrl(api); final boolean reboot=autoReboot.isChecked(); append("开始烧录："+t.name+" · PID="+t.pid);
        worker.execute(()->{try{
            WriterService.Result r=WriterService.write(api,device,t,reboot,message->runOnUiThread(()->append(message)));
            runOnUiThread(()->{append("完成：PID="+r.pid+"，DID="+r.did+"，MAC="+r.mac);toast("授权文件写入成功");});
        }catch(Exception e){runOnUiThread(()->{append("烧录失败："+e.getMessage());toast("烧录失败");});}});
    }
    private void syncRestart() {
        final ProductTemplate t=currentTemplate();final String device=host.getText().toString().trim();if(device.isEmpty()){toast("请输入设备 IP");return;}
        new AlertDialog.Builder(this).setTitle("同步并重启").setMessage("将连接 "+device+"，执行 sync 后重启设备。")
            .setNegativeButton("取消",null).setPositiveButton("执行",(d,w)->{append("正在同步并重启 "+device);worker.execute(()->{try{WriterService.syncAndReboot(device,t);runOnUiThread(()->append("已发送同步和重启命令"));}catch(Exception e){runOnUiThread(()->append("操作失败："+e.getMessage()));}});}).show();
    }
    private void append(String line) { runOnUiThread(()->{if(logView==null)return;String old=logView.getText().toString();if(old.equals(getString(R.string.log_waiting)))old="";logView.setText(old+line+"\n");}); }
    @Override protected void onPause(){super.onPause();if(host!=null)store.setHost(host.getText().toString());if(autoReboot!=null)store.setAutoReboot(autoReboot.isChecked());}
    @Override protected void onDestroy(){super.onDestroy();worker.shutdownNow();}
}
