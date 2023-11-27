package com.touchmediaproductions.pneumocheck.ui.submissions;

import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;
import com.touchmediaproductions.pneumocheck.helpers.DateHelper;
import com.touchmediaproductions.pneumocheck.helpers.DownloadHelper;
import com.touchmediaproductions.pneumocheck.helpers.FirestoreRepository;
import com.touchmediaproductions.pneumocheck.ml.MLHelper;
import com.touchmediaproductions.pneumocheck.models.Submission;
import com.touchmediaproductions.pneumocheck.models.SubmissionModel;
import com.touchmediaproductions.pneumocheck.models.UserProfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

public class SubmissionsViewModel extends ViewModel {
    private static final String TAG = "SubmissionViewModel";
    private MutableLiveData<List<SubmissionModel>> submissionsMutableLiveData;

    private LinkedList<ListenerRegistration> snapShotListeners = new LinkedList<>();

    @Override
    protected void onCleared() {
        super.onCleared();
        if (snapShotListeners != null) {
            for (ListenerRegistration listenerRegistration : snapShotListeners) {
                listenerRegistration.remove();
            }
        }
    }

    public LiveData<List<SubmissionModel>> getAllSubmissionsForUser(String userId) {
        if (submissionsMutableLiveData == null) {
            submissionsMutableLiveData = new MutableLiveData<>();
            fetchLiveSubmissions(userId);
        }
        return submissionsMutableLiveData;
    }

    public LiveData<List<SubmissionModel>> forceRefreshAllSubmissionsForUser(String userId) {
        submissionsMutableLiveData = new MutableLiveData<>();
        fetchLiveSubmissions(userId);
        return submissionsMutableLiveData;
    }

    public void fetchLiveSubmissions(final String userId) {
        snapShotListeners.add(FirestoreRepository.getUserProfile(userId).addSnapshotListener(new EventListener<DocumentSnapshot>() {
            @Override
            public void onEvent(@Nullable DocumentSnapshot value, @Nullable FirebaseFirestoreException error) {
                if (value.exists()) {
                    UserProfile userProfile = value.toObject(UserProfile.class);
                    String displayName = userProfile.getDisplayName();

                    String lastName = "";
                    String firstName = "";
                    if (displayName.split("\\w+").length > 1) {
                        lastName = displayName.substring(displayName.lastIndexOf(" ") + 1);
                        firstName = displayName.substring(0, displayName.lastIndexOf(' '));
                    } else {
                        firstName = displayName;
                    }

                    int age = 0;
                    String sex = userProfile.getSex();
                    sex = (sex == null) ? "" : sex;
                    try {
                        age = Integer.parseInt(userProfile.getAge());
                    } catch (Exception ex) {

                    }

                    final String finalFirstName = firstName;
                    final String finalLastName = lastName;
                    final int finalAge = age;
                    final String finalSex = sex;
                    FirestoreRepository.getSubmissionForUser(userId).addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
                        @Override
                        public void onComplete(@NonNull final Task<QuerySnapshot> task) {
                            if (task.isSuccessful()) {

                                new Thread(new Runnable() {
                                    @RequiresApi(api = Build.VERSION_CODES.N)
                                    @Override
                                    public void run() {
                                        //Prepare a list to populate:
                                        ArrayList<SubmissionModel> submissionModelArrayList = new ArrayList<>();

                                        //Iterate through the submissions to build a SubmissionModel object for each
                                        for (QueryDocumentSnapshot document : task.getResult()) {
                                            String submissionId = document.getId();
                                            Submission submission = document.toObject(Submission.class);

                                            byte[] imageByteArray = new byte[0];
                                            try {
                                                imageByteArray = DownloadHelper.downloadImageBytes(submission.getImageUrl());
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }

                                            SubmissionModel.Confirmation confirmation = SubmissionModel.Confirmation.valueOf(submission.getPrediction());
                                            MLHelper.Prediction probabilities = null;
                                            if (submission.getProbabilities() != null) {
                                                probabilities = new MLHelper.Prediction(submission.getProbabilities());
                                            }

                                            SubmissionModel submissionModel = new SubmissionModel(submissionId,
                                                    userId,
                                                    finalFirstName,
                                                    finalLastName,
                                                    DateHelper.fromIso8601StringBackToDate(submission.getDateOfScan()),
                                                    DateHelper.fromIso8601StringBackToDate(submission.getSubmissionDate()),
                                                    confirmation,
                                                    probabilities,
                                                    imageByteArray,
                                                    SubmissionModel.XrayType.valueOf(submission.getImageType().toUpperCase()),
                                                    submission.getLearntAt());

                                            submissionModelArrayList.add(submissionModel);


                                        }

                                        // Order the list by submission date
                                        submissionModelArrayList.sort(
                                                Comparator.comparing(
                                                        SubmissionModel::getSubmissionCreationDate,
                                                        Comparator.nullsLast(Comparator.naturalOrder())
                                                )
                                        );

                                        submissionsMutableLiveData.postValue(submissionModelArrayList);
                                    }
                                }).start();

                            } else {
                                Log.d(TAG, "Error getting documents: ", task.getException());
                            }
                        }
                    });
                }
            }
        }));

    }
}
