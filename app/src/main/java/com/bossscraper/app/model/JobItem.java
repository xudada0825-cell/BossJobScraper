package com.bossscraper.app.model;

public class JobItem {
    private String jobTitle;
    private String companyName;
    private String companyAddress;
    private String city;
    private String district;
    private String salary;
    private String publishTime;
    private String jobUrl;
    private String companyType;
    private String companyScale;

    public JobItem() {}

    public JobItem(String jobTitle, String companyName, String companyAddress,
                   String city, String district, String salary, String publishTime,
                   String jobUrl, String companyType, String companyScale) {
        this.jobTitle = jobTitle;
        this.companyName = companyName;
        this.companyAddress = companyAddress;
        this.city = city;
        this.district = district;
        this.salary = salary;
        this.publishTime = publishTime;
        this.jobUrl = jobUrl;
        this.companyType = companyType;
        this.companyScale = companyScale;
    }

    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String jobTitle) { this.jobTitle = jobTitle; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getCompanyAddress() { return companyAddress; }
    public void setCompanyAddress(String companyAddress) { this.companyAddress = companyAddress; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }

    public String getSalary() { return salary; }
    public void setSalary(String salary) { this.salary = salary; }

    public String getPublishTime() { return publishTime; }
    public void setPublishTime(String publishTime) { this.publishTime = publishTime; }

    public String getJobUrl() { return jobUrl; }
    public void setJobUrl(String jobUrl) { this.jobUrl = jobUrl; }

    public String getCompanyType() { return companyType; }
    public void setCompanyType(String companyType) { this.companyType = companyType; }

    public String getCompanyScale() { return companyScale; }
    public void setCompanyScale(String companyScale) { this.companyScale = companyScale; }

    public String getFullAddress() {
        if (city != null && district != null && !district.isEmpty()) {
            return city + " · " + district;
        }
        return city != null ? city : (companyAddress != null ? companyAddress : "未知地址");
    }
}
