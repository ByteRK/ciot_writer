package com.ciot.writer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final int BLUE = Color.rgb(28, 106, 132), DARK = Color.rgb(31, 48, 60), MUTED = Color.rgb(103, 122, 134);
    private TemplateStore store;
    private List<ProductTemplate> templates;
    private Spinner templateSpinner;
    private EditText serverUrl, host;
    private CheckBox autoReboot;
    private TextView logView;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final int IMPORT_JSON = 3101, EXPORT_JSON = 3102;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new TemplateStore(this); templates = store.load();
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(28)); page.setBackgroundColor(Color.rgb(244,247,248)); scroll.addView(page);

        TextView title = text("CIOT Writer", 27, DARK, true); page.addView(title);
        TextView subtitle = text("嵌入式设备授权写入", 14, MUTED, false); page.addView(subtitle, params(0, 8));

        LinearLayout serverCard = card(page, "授权服务器");
        serverUrl = input("例如 http://192.168.1.10:8080", false); serverUrl.setText(store.getServerUrl()); serverCard.addView(serverUrl, params(0, 8));
        LinearLayout serverActions = row(serverCard);
        Button saveServer = button("保存地址"); serverActions.addView(saveServer, new LinearLayout.LayoutParams(0,dp(48),1));
        Button testServer = button("测试连接"); testServer.setBackgroundColor(BLUE); testServer.setTextColor(Color.WHITE); LinearLayout.LayoutParams testParams=new LinearLayout.LayoutParams(0,dp(48),1);testParams.setMargins(dp(8),0,0,0);serverActions.addView(testServer,testParams);
        saveServer.setOnClickListener(v -> { store.setServerUrl(serverUrl.getText().toString()); toast("服务器地址已保存"); });
        testServer.setOnClickListener(v -> checkServer(testServer));

        LinearLayout productCard = card(page, "产品与烧录模板");
        templateSpinner = new Spinner(this); productCard.addView(templateSpinner, params(0, 6)); refreshSpinner(store.getSelected());
        LinearLayout templateActions = row(productCard);
        addButton(templateActions, "新增", () -> editTemplate(null));
        addButton(templateActions, "编辑", () -> editTemplate(currentTemplate()));
        addButton(templateActions, "删除", this::deleteTemplate);
        LinearLayout jsonActions = row(productCard);
        addButton(jsonActions, "导入 JSON", this::importTemplates);
        addButton(jsonActions, "导出 JSON", this::exportTemplates);
        templateSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { store.setSelected(position); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        LinearLayout deviceCard = card(page, "设备连接");
        TextView hint = text("手机和设备需连接同一局域网。", 13, MUTED, false);
        deviceCard.addView(hint, params(0, 2));
        host = input("设备 IP 地址", false); deviceCard.addView(host, params(0, 12));
//        TextView templateSummary = text("Telnet 端口、账号、网卡名、设备文件路径和内容模板由所选产品配置。", 12, MUTED, false);
//        deviceCard.addView(templateSummary, params(0, 8));
        autoReboot = new CheckBox(this); autoReboot.setText("写入并同步后自动重启设备"); autoReboot.setTextColor(DARK); deviceCard.addView(autoReboot, params(0, 4));
        Button check = button("测试 Telnet 连接"); deviceCard.addView(check, params(0, 9));
        check.setOnClickListener(v -> runCheck());
        Button burn = button("申请授权并烧录"); burn.setBackgroundColor(BLUE); burn.setTextColor(Color.WHITE); deviceCard.addView(burn, params(0, 8));
        burn.setOnClickListener(v -> burn());
        Button syncRestart = button("同步并重启"); syncRestart.setBackgroundColor(Color.rgb(74, 101, 112)); syncRestart.setTextColor(Color.WHITE); deviceCard.addView(syncRestart, params(0, 8));
        syncRestart.setOnClickListener(v -> syncRestart());

        LinearLayout logCard = card(page, "操作记录");
        logView = text("等待操作…", 13, DARK, false); logView.setTypeface(Typeface.MONOSPACE); logView.setMinHeight(dp(76)); logCard.addView(logView, params(0, 4));
        setContentView(scroll);
    }

    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16),dp(15),dp(16),dp(15));
        box.setBackgroundColor(Color.WHITE); box.setElevation(dp(2)); LinearLayout.LayoutParams lp=params(0,16); lp.topMargin=dp(16); parent.addView(box,lp);
        TextView label=text(heading,17,DARK,true); box.addView(label,params(0,4)); return box;
    }
    private LinearLayout row(LinearLayout parent) { LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); parent.addView(r,params(0,4)); return r; }
    private void addButton(LinearLayout row, String label, Runnable action) { Button b=button(label); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(46),1); lp.setMargins(dp(3),dp(3),dp(3),dp(3)); row.addView(b,lp); b.setOnClickListener(v->action.run()); }
    private Button button(String value) { Button b=new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(DARK); return b; }
    private TextView text(String value,int size,int color,boolean bold) { TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); if(bold)t.setTypeface(null,Typeface.BOLD); return t; }
    private EditText input(String hint, boolean multiline) { EditText e=new EditText(this); e.setHint(hint); e.setTextSize(15); e.setSingleLine(!multiline); e.setPadding(dp(12),dp(8),dp(12),dp(8)); if(multiline){e.setGravity(Gravity.TOP|Gravity.START);e.setMinLines(3);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);} return e; }
    private LinearLayout.LayoutParams params(int width,int top) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width==0?ViewGroup.LayoutParams.MATCH_PARENT:width,ViewGroup.LayoutParams.WRAP_CONTENT); p.topMargin=dp(top); return p; }
    private int dp(int value) { return (int)(value*getResources().getDisplayMetrics().density+0.5f); }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_SHORT).show(); }
    private ProductTemplate currentTemplate() { int i=templateSpinner==null?0:templateSpinner.getSelectedItemPosition(); return templates.get(Math.max(0,Math.min(i,templates.size()-1))); }

    private void refreshSpinner(int selected) {
        if(templateSpinner==null)return;
        List<String> names=new ArrayList<>(); for(ProductTemplate t:templates) names.add(t.name+"  ·  PID "+(t.pid.isEmpty()?"未设置":t.pid));
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,names); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); templateSpinner.setAdapter(adapter);
        int index=Math.max(0,Math.min(selected,templates.size()-1)); templateSpinner.setSelection(index); store.setSelected(index);
    }

    private void editTemplate(ProductTemplate original) {
        final boolean editing=original!=null; final ProductTemplate t=editing?original:new ProductTemplate();
        LinearLayout form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20),0,dp(20),0);
        ScrollView scroller=new ScrollView(this); scroller.addView(form);
        EditText name=field(form,"产品名称",t.name,false), pid=field(form,"PID",t.pid,false), nic=field(form,"设备网卡名",t.networkInterface,false);
        EditText path=field(form,"设备端文件绝对路径",t.targetPath,false), port=field(form,"Telnet 端口",String.valueOf(t.port),false);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText username=field(form,"Telnet 用户名",t.username,false), password=field(form,"Telnet 密码",t.password,false);
        EditText content=field(form,"文件内容模板（必须含 {{pid}}、{{did}}、{{license}}）",t.contentTemplate,true); content.setMinLines(5);
        new AlertDialog.Builder(this).setTitle(editing?"编辑产品模板":"新增产品模板").setView(scroller)
            .setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
                String p=pid.getText().toString().trim(), n=name.getText().toString().trim(), iface=nic.getText().toString().trim(), target=path.getText().toString().trim(), body=content.getText().toString();
                int telnetPort; try{telnetPort=Integer.parseInt(port.getText().toString());}catch(Exception ex){toast("Telnet 端口无效");return;}
                if(n.isEmpty()||p.isEmpty()||!iface.matches("[A-Za-z0-9_.:-]{1,64}")||!target.startsWith("/")||telnetPort<1||telnetPort>65535){toast("请检查名称、PID、网卡、绝对路径和端口");return;}
                for(String slot:new String[]{"{{pid}}","{{did}}","{{license}}"})if(!body.contains(slot)){toast("文件模板缺少 "+slot);return;}
                t.name=n;t.pid=p;t.networkInterface=iface;t.targetPath=target;t.port=telnetPort;t.username=username.getText().toString();t.password=password.getText().toString();t.contentTemplate=body;
                if(!editing)templates.add(t); store.save(templates);refreshSpinner(templates.indexOf(t));toast("模板已保存");
            }).show();
    }
    private EditText field(LinearLayout form,String label,String value,boolean secret) {
        TextView title=text(label,13,MUTED,false); LinearLayout.LayoutParams lp=params(0,5);form.addView(title,lp);
        EditText e=input(label,true); e.setText(value); if(!secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE); else e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(e,params(0,0)); return e;
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
    private void append(String line) { runOnUiThread(()->{if(logView==null)return;String old=logView.getText().toString();if(old.equals("等待操作…"))old="";logView.setText(old+line+"\n");}); }
    @Override protected void onDestroy(){super.onDestroy();worker.shutdownNow();}
}
