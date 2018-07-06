package org.treeleafj.xdoc.resolver.javaparser.converter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treeleafj.xdoc.model.FieldInfo;
import org.treeleafj.xdoc.model.ObjectInfo;
import org.treeleafj.xdoc.utils.CommentUtils;
import org.treeleafj.xdoc.tag.DocTag;
import org.treeleafj.xdoc.tag.SeeTagImpl;
import org.treeleafj.xdoc.utils.ClassMapperUtils;
import org.treeleafj.xdoc.utils.Constant;

import java.beans.PropertyDescriptor;
import java.io.FileInputStream;
import java.util.*;

/**
 * Created by leaf on 2017/3/4.
 */
public class SeeTagConverter extends DefaultJavaParserTagConverterImpl {

    private Logger log = LoggerFactory.getLogger(SeeTagConverter.class);

    @Override
    public DocTag converter(String comment) {
        DocTag _docTag = super.converter(comment);

        String path = ClassMapperUtils.getPath((String) _docTag.getValues());
        if (StringUtils.isBlank(path)) {
            return null;
        }

        Class<?> returnClassz;
        CompilationUnit cu;
        try (FileInputStream in = new FileInputStream(path)) {
            cu = JavaParser.parse(in);
            if (cu.getTypes().size() <= 0) {
                return null;
            }
            returnClassz = Class.forName(cu.getPackageDeclaration().get().getNameAsString() + "." + cu.getTypes().get(0).getNameAsString());

        } catch (Exception e) {
            log.warn("读取java原文件失败:{}", path, e.getMessage());
            return null;
        }

        String text = cu.getComment().isPresent() ? CommentUtils.parseCommentText(cu.getComment().get().getContent()) : "";

        Map<String, String> commentMap = this.analysisFieldComments(returnClassz);
        List<FieldInfo> fields = this.analysisFields(returnClassz, commentMap);

        ObjectInfo objectInfo = new ObjectInfo();
        objectInfo.setType(returnClassz);
        objectInfo.setFieldInfos(fields);
        objectInfo.setComment(text);
        return new SeeTagImpl(_docTag.getTagName(), objectInfo);
    }

    private Map<String, String> analysisFieldComments(Class<?> classz) {

        final Map<String, String> commentMap = new HashMap();

        List<Class> classes = new LinkedList<>();

        Class nowClass = classz;

        //获取所有的属性注释(包括父类的)
        while (true) {
            classes.add(0, nowClass);
            if (Object.class.equals(nowClass) || Object.class.equals(nowClass.getSuperclass())) {
                break;
            }
            nowClass = nowClass.getSuperclass();
        }

        //反方向循环,子类属性注释覆盖父类属性
        for (Class clz : classes) {
            String path = ClassMapperUtils.getPath(clz.getSimpleName());
            if (StringUtils.isBlank(path)) {
                continue;
            }
            try (FileInputStream in = new FileInputStream(path)) {
                CompilationUnit cu = JavaParser.parse(in);

                new VoidVisitorAdapter<Void>() {
                    @Override
                    public void visit(FieldDeclaration n, Void arg) {
                        String name = n.getVariable(0).getName().asString();

                        String comment = "";
                        if (n.getComment().isPresent()) {
                            comment = n.getComment().get().getContent();
                        }

                        if (name.contains("=")) {
                            name = name.substring(0, name.indexOf("=")).trim();
                        }

                        commentMap.put(name, CommentUtils.parseCommentText(comment));
                    }
                }.visit(cu, null);

            } catch (Exception e) {
                log.warn("读取java原文件失败:{}", path, e.getMessage(), e);
            }
        }

        return commentMap;
    }

    private List<FieldInfo> analysisFields(Class classz, Map<String, String> commentMap) {
        PropertyDescriptor[] propertyDescriptors = PropertyUtils.getPropertyDescriptors(classz);


        List<FieldInfo> fields = new ArrayList<>();

        for (PropertyDescriptor propertyDescriptor : propertyDescriptors) {
            //排除掉class属性
            if (propertyDescriptor.getName().equals("class")) {
                continue;
            }

            FieldInfo field = new FieldInfo();
            field.setType(propertyDescriptor.getPropertyType());
            field.setSimpleTypeName(propertyDescriptor.getPropertyType().getSimpleName());
            field.setName(propertyDescriptor.getName());
            String comment = commentMap.get(propertyDescriptor.getName());
            if (StringUtils.isBlank(comment)) {
                field.setComment("");
                field.setRequire(false);
                fields.add(field);
            } else {
                boolean require = false;
                if (comment.contains("|")) {
                    int endIndex = comment.lastIndexOf("|" + Constant.YES_ZH);
                    if (endIndex < 0) {
                        endIndex = comment.lastIndexOf("|" + Constant.YES_EN);
                    }
                    require = endIndex > 0;

                    if (require) {
                        comment = comment.substring(0, endIndex);
                    }
                }

                field.setComment(comment);
                field.setRequire(require);
                fields.add(field);
            }
        }
        return fields;
    }

}
