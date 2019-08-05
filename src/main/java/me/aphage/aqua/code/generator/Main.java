package me.aphage.aqua.code.generator;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Main {
    private static final String SQL_URL= "jdbc:mysql://localhost:3306?useUnicode=true&characterEncoding=utf8&autoReconnect=true&failOverReadOnly=false&useSSL=false&zeroDateTimeBehavior=convertToNull&serverTimezone=Asia/Shanghai";
    private static final String schema = "oa";
    private static final String QUERY_TABLE_INFO = "select * from information_schema.tables where table_schema = (select database())";
    private static final String QUERY_COLUMN_INFO = "select * from information_schema.columns where table_name = ? and table_schema = (select database()) order by ordinal_position";

    private static final LinkedHashMap<String,Object> yml = init_yml();

    static {
        try {
            Class.forName("com.mysql.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
    private static List<Map<String,Object>> get_table_info(Function<SQLException,Void> error){
        var tables = new ArrayList<Map<String,Object>>();
        try (var conn = DriverManager.getConnection(SQL_URL,"root","root")){
            try(var statement = conn.createStatement();var result = statement.executeQuery("use " + schema)){
            }
            try(var statement = conn.createStatement();var result = statement.executeQuery(QUERY_TABLE_INFO)){
                each_result(result,tables);
            }
            tables.forEach(n->{
                var columns = new ArrayList<Map<String,Object>>();
                try(var statement = conn.prepareStatement(QUERY_COLUMN_INFO)){
                    statement.setString(1,(String) n.get("TABLE_NAME"));
                    try(var result = statement.executeQuery()){
                        each_result(result,columns);
                    }
                }catch (SQLException e_statement){
                    error.apply(e_statement);
                }
                columns.stream().filter(nn->nn.get("COLUMN_KEY") != null && "PRI".equals(nn.get("COLUMN_KEY"))).findAny().ifPresent(nn->n.put("KEY_INFO",nn));
                n.put("COLUMNS_INFO",columns);
            });
        }catch (SQLException e_connection){
            error.apply(e_connection);
        }
        return tables;
    }

    public static void main(String[] args){
        var date_format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss ");

        String author = yml_get("author");
        String base_namespace = yml_get("base-namespace");
        String java_out = yml_get("java-out");
        String resoutces_out = yml_get("resoutces-out");
        String table_prefix = yml_get("table-prefix");
        String columns_prefix = yml_get("columns-prefix");
        List<Map<String,Object>> templates = yml_get("templates");
        if(templates == null || templates.size() == 0 ){
            System.out.println("请配置模板");
            return;
        }
        Map<String,Object> data_type = yml_get("data-type");

        //int(10) unsigned

        var bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes);

        Properties properties = new Properties();
        properties.setProperty("resource.loader", "class");
        properties.setProperty("class.resource.loader.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
        VelocityEngine velocityEngine = new VelocityEngine(properties);
        var tables = get_table_info(e->{
            throw new RuntimeException(e);
        });
        tables.forEach(table->{
            var context = new LinkedHashMap<String,Object>();
            context.put("author",author);
            context.put("create-time",date_format.format(new Date()));
            context.put("this-table",table);
            var t_table = (String) table.get("TABLE_NAME");

            if(table_prefix == null || table_prefix.length() == 0 || !t_table.startsWith(table_prefix))
                context.put("Model-Name",line_to_hump(t_table));
            else
                context.put("Model-Name",line_to_hump(t_table.substring(table_prefix.length())));
            var line = (String)context.get("Model-Name");
            context.put("model-name",hump_to_line((String)context.get("Model-Name")));

            line = line.substring(0,1).toUpperCase()+(line.length()>1?line.substring(1):"");
            context.put("Model-Name",line);

            ((List<Map<String,Object>>)table.get("COLUMNS_INFO")).forEach(column->{
                var type = (String)column.get("DATA_TYPE");
                var column_type = (String)column.get("COLUMN_TYPE");
                if(column_type != null && column_type.contains("unsigned")){
                    column_type = (String) data_type.get("u"+type);
                    if(column_type != null)type = column_type;
                }else{
                    type = (String) data_type.get(type);
                }
                column.put("TYPE",type);
                //columns_prefix
                var t_column_name = (String)column.get("COLUMN_NAME");
                if(columns_prefix == null || columns_prefix.length() == 0 || !t_column_name.startsWith(columns_prefix))
                    column.put("NAME",line_to_hump(t_column_name));
                else
                    column.put("NAME",line_to_hump(t_column_name.substring(columns_prefix.length())));
            });

            templates.forEach(template->{
                var namespace = (String)template.get("namespace");
                var t_template = (String)template.get("template");
                var t_out = (String)template.get("out");

                if(namespace != null && namespace.length() != 0)
                    context.put("namespace",namespace.replace("${base-namespace}",base_namespace));
                t_out = t_out.replace("${resoutces-out}",resoutces_out)
                        .replace("${java-out}",java_out)
                        .replace("#{Model-Name}",(String)context.get("Model-Name"));

                var tpl = velocityEngine.getTemplate(t_template,"UTF-8");
                var string_writer = new StringWriter();
                tpl.merge(new VelocityContext(context),string_writer);
                try {
                    zip.putNextEntry(new ZipEntry(t_out));
                    zip.write(string_writer.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Exception e){
                    throw new RuntimeException(e);
                }
            });

        });

        var file = new File((String)yml_get("out"));
        try {
            file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try (var output_stream = new FileOutputStream(file);){
            zip.close();
            output_stream.write(bytes.toByteArray());
            output_stream.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private final static Pattern linePattern = Pattern.compile("_(\\w)");
    /**
     * @date 2019/8/4 20:37
     * @author aphage
     * @version 1.0
     * @return java.lang.String
     * @param str
    */
    public static String line_to_hump(String str) {
        str = str.toLowerCase();
        Matcher matcher = linePattern.matcher(str);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, matcher.group(1).toUpperCase());
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static Pattern humpPattern = Pattern.compile("[A-Z]");
    /**
     * @date 2019/8/4 19:21
     * @author aphage
     * @version 1.0
     * @return java.lang.String
     * @param str
    */
    public static String hump_to_line(String str) {
        Matcher matcher = humpPattern.matcher(str);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "_" + matcher.group(0).toLowerCase());
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static LinkedHashMap<String,Object> init_yml(){
        var yml = new Yaml();
        return yml.load(Main.class.getResourceAsStream("/app.yml"));
    }

    private static <T> T yml_get(String properties){
        if(properties == null || properties.length() == 0)
            return null;

        var names = properties.split("\\.");
        if(names.length == 0)
            return null;
        Object o = null;
        o = yml.get(names[0]);
        if(names.length == 1)
            return (T)o;

        for(var i = 1;i<names.length;++i){
            if(o instanceof LinkedHashMap){
                o=((LinkedHashMap) o).get(names[i]);
            } else if(o instanceof List){
                return null;
            }
        }
        return (T)o;
    }

    private static void each_result(ResultSet result, List<Map<String,Object>> ret) throws SQLException{
        var meta_data = result.getMetaData();
        while(result.next()){
            var n = new LinkedHashMap<String,Object>();
            for(int i=0; i<meta_data.getColumnCount(); ++i){
                var label = meta_data.getColumnLabel(i+1);
                n.put(label,result.getObject(label));
            }
            ret.add(n);
        }
    }

}
