package com.bossscraper.app.adapter;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bossscraper.app.R;
import com.bossscraper.app.model.JobItem;

import java.util.ArrayList;
import java.util.List;

public class JobAdapter extends RecyclerView.Adapter<JobAdapter.ViewHolder> {

    private List<JobItem> jobs = new ArrayList<>();
    private final Context context;

    public JobAdapter(Context context) {
        this.context = context;
    }

    public void setJobs(List<JobItem> newJobs) {
        this.jobs = newJobs != null ? newJobs : new ArrayList<>();
        notifyDataSetChanged();
    }

    public int getJobCount() {
        return jobs.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_job, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        JobItem job = jobs.get(position);

        holder.tvJobTitle.setText(job.getJobTitle());
        holder.tvCompanyName.setText(job.getCompanyName());
        holder.tvAddress.setText(job.getFullAddress());
        holder.tvSalary.setText(job.getSalary());
        holder.tvPublishTime.setText(job.getPublishTime());

        String type = job.getCompanyType();
        holder.tvCompanyType.setText((type != null && !type.isEmpty()) ? type : "贸易/进出口");

        String scale = job.getCompanyScale();
        holder.tvCompanyScale.setText((scale != null && !scale.isEmpty()) ? scale : "");

        holder.itemView.setOnClickListener(v -> {
            String url = job.getJobUrl();
            if (url != null && !url.isEmpty() && url.startsWith("http")) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    context.startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(context,
                        job.getCompanyName() + "\n" + job.getFullAddress(),
                        Toast.LENGTH_LONG).show();
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            String info = "公司：" + job.getCompanyName()
                    + "\n地址：" + job.getFullAddress()
                    + "\n岗位：" + job.getJobTitle()
                    + "\n薪资：" + job.getSalary();
            Toast.makeText(context, info, Toast.LENGTH_LONG).show();
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return jobs.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvJobTitle, tvCompanyName, tvAddress, tvSalary,
                tvPublishTime, tvCompanyType, tvCompanyScale;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvJobTitle = itemView.findViewById(R.id.tvJobTitle);
            tvCompanyName = itemView.findViewById(R.id.tvCompanyName);
            tvAddress = itemView.findViewById(R.id.tvAddress);
            tvSalary = itemView.findViewById(R.id.tvSalary);
            tvPublishTime = itemView.findViewById(R.id.tvPublishTime);
            tvCompanyType = itemView.findViewById(R.id.tvCompanyType);
            tvCompanyScale = itemView.findViewById(R.id.tvCompanyScale);
        }
    }
}
