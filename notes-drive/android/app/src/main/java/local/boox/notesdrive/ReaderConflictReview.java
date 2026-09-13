package local.boox.notesdrive;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.*;
import org.json.*;

/** Explicit whole-book selection; all prior reading versions remain in Drive. */
final class ReaderConflictReview {
    static void show(Activity activity,DriveSession session) {
        String account=session.boundAccount(),folder=session.folderId;
        session.reviewReaderConflicts(books->{
            if(activity.isFinishing()||activity.isDestroyed())return;
            if(books.length()==0) {
                new AlertDialog.Builder(activity).setTitle("No conflicting reading versions")
                    .setMessage("The verified Drive check found one current version per book.")
                    .setPositiveButton("OK",null).show();
                return;
            }
            String[] titles=new String[books.length()];
            for(int i=0;i<titles.length;i++)titles[i]=books.optJSONObject(i).optString("title");
            new AlertDialog.Builder(activity).setTitle("Review reading versions").setItems(titles,(dialog,index)->{
                JSONObject book=books.optJSONObject(index);JSONArray versions=book.optJSONArray("versions");
                List<String> heads=new ArrayList<>();String[] labels=new String[versions.length()];
                for(int i=0;i<labels.length;i++) {
                    heads.add(versions.optJSONObject(i).optString("id"));
                    labels[i]=versions.optJSONObject(i).optString("label");
                }
                new AlertDialog.Builder(activity).setTitle(book.optString("title")).setItems(labels,(picker,choice)->
                    new AlertDialog.Builder(activity).setTitle("Use this reading version?")
                        .setMessage(labels[choice]+"\n\nThis selects the entire book and its reading data. "+
                            "It does not combine annotations or handwriting. Other versions remain in Drive history. "+
                            "Close NeoReader before continuing.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Use this version",(confirmation,which)->
                            session.resolveReaderConflict(account,folder,book.optString("id"),heads,heads.get(choice))).show()
                ).setNegativeButton("Cancel",null).show();
            }).setNegativeButton("Cancel",null).show();
        });
    }
}
