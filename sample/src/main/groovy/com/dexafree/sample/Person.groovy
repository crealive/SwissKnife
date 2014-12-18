package com.dexafree.sample

import android.os.Parcel
import com.arasthel.swissknife.annotations.Parcelable
import groovy.transform.CompileStatic;

@Parcelable(exclude={name;age;friends})
@CompileStatic
public class Person {

    private String name;
    private int age;
    private String[] friends = ["Juan", "Lucas"]
    int[] phones = [123, 456, 789]
    private ArrayList<String> cities = ["Almería"] as ArrayList<String>
    private ParcelableClass aParcelable = new ParcelableClass();

    String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    int getAge() {
        return age;
    }

    void setAge(int age) {
        this.age = age;
    }

    public Person(String name, int age){
        this.name = name;
        this.age = age;
    }

}
